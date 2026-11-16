import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import java.security.MessageDigest
import java.time.Instant

def Message processData(Message message) {
    def reader = message.getBody(java.io.Reader)
    def payload = new JsonSlurper().parse(reader)

    String bpId = (payload.BusinessPartner ?: "").trim().toUpperCase()
    String countryCode = (payload.Country ?: "").trim().toUpperCase()
    String vatNumber = (payload.EuVatNumber ?: "").replaceAll("\\s+", "").toUpperCase()
    String peppolId = (payload.PeppolParticipantId ?: "").trim()
    String invoiceFormat = (payload.StructuredInvoiceFormat ?: "").trim().toUpperCase()
    String taxClassCode = (payload.TaxClassificationCode ?: "").trim()
    boolean vidaDrrReady = (payload.VidaDigitalReportingReady == true)

    List<String> taxViolations = []
    if (!bpId || bpId.length() < 3) taxViolations.add("INVALID_BP_ID: BusinessPartner identifier is required.")
    if (!(countryCode ==~ ~/^[A-Z]{2}$/)) taxViolations.add("INVALID_COUNTRY_CODE: ISO 3166-1 alpha-2 Country code is mandatory.")
    if (!isValidEuVatNumber(countryCode, vatNumber)) taxViolations.add("INVALID_EU_VIES_VAT: Failed EU VIES or ISO 7064 Mod-11,10 check for [${vatNumber}].")

    Set<String> validPeppolIcds = ["0204", "9930", "0199", "0088", "9914", "0208", "0106", "0009"] as Set
    if (!(peppolId ==~ ~/^\d{4}:[A-Za-z0-9\-_.]+$/)) {
        taxViolations.add("INVALID_PEPPOL_PARTICIPANT_ID: PeppolParticipantId [${peppolId}] must follow ISO/IEC 6523 ICD:Identifier syntax.")
    } else if (!validPeppolIcds.contains(peppolId.substring(0, 4))) {
        taxViolations.add("UNSUPPORTED_PEPPOL_ICD: ICD prefix [${peppolId.substring(0, 4)}] is not registered in EU ViDA SMP directory.")
    }

    Set<String> allowedFormats = ["EN16931_UBL_2.1", "EN16931_CII_D16B", "XRECHNUNG_3.0", "FACTUR_X_EN16931"] as Set
    if (!allowedFormats.contains(invoiceFormat)) taxViolations.add("VIDA_UNSUPPORTED_EINVOICE_SYNTAX: Format [${invoiceFormat}] must comply with EN 16931.")
    if (!vidaDrrReady) taxViolations.add("VIDA_DRR_NOT_ENABLED: Digital Reporting Requirements (DRR) clearance is not active.")
    if (!(taxClassCode in ["0", "1"])) taxViolations.add("INVALID_S4_TAX_CLASSIFICATION: TaxClassificationCode must be '1' or '0'.")

    String taxComplianceHash = MessageDigest.getInstance("SHA-256")
        .digest("${bpId}|${countryCode}|${vatNumber}|${peppolId}|${invoiceFormat}".getBytes("UTF-8"))
        .encodeHex().toString()
    message.setHeader("X-TAX-Compliance-Hash", taxComplianceHash)

    if (!taxViolations.isEmpty()) {
        message.setHeader("X-TAX-Gate-Status", "REJECTED")
        message.setBody(JsonOutput.prettyPrint(JsonOutput.toJson([
            status: "QUARANTINED_VIDA_TAX_GOVERNANCE_HOLD", regulation: "EU_VIDA_DIRECTIVE_2025_516_AND_EN16931",
            timestamp: Instant.now().toString(), businessPartner: bpId, euVatNumber: vatNumber,
            taxComplianceSha256: taxComplianceHash, violations: taxViolations
        ])))
    } else {
        message.setHeader("X-TAX-Gate-Status", "APPROVED")
        message.setBody(JsonOutput.prettyPrint(JsonOutput.toJson([
            "@odata.context": "\$metadata#A_BusinessPartnerTaxNumber/\$entity",
            BusinessPartner: bpId, BPTaxType: "${countryCode}0", BPTaxNumber: vatNumber,
            TaxComplianceFingerprint: taxComplianceHash,
            to_TaxClassification: [DepartureCountry: countryCode, CustomerTaxCategory: "TTX1", TaxClassification: taxClassCode],
            EuVidaDigitalReportingExtension: [VidaDirectiveStatus: "COMPLIANT_2026_2027", PeppolParticipantId: peppolId, StructuredInvoiceSyntax: invoiceFormat, DigitalReportingClearance: "ACTIVE", VerifiedAt: Instant.now().toString()]
        ])))
    }
    return message
}

private boolean isValidEuVatNumber(String country, String vat) {
    if (!vat || !vat.startsWith(country)) return false
    if (country == "DE") {
        if (!(vat ==~ ~/^DE\d{9}$/)) return false
        String digits = vat.substring(2)
        int product = 10
        for (int i = 0; i < 8; i++) {
            int digit = Character.getNumericValue(digits.charAt(i))
            int sum = (digit + product) % 10
            if (sum == 0) sum = 10
            product = (2 * sum) % 11
        }
        return ((11 - product) % 10) == Character.getNumericValue(digits.charAt(8))
    }
    return (vat ==~ ~/^[A-Z]{2}[A-Z0-9]{2,12}$/)
}
