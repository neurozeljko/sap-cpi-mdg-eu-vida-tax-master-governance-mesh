import spock.lang.Specification
import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import com.sap.gateway.ip.core.customdev.util.Message

class VidaTaxMasterValidatorSpec extends Specification {
    def script = new GroovyShell().parse(new File("src/main/groovy/TAX_ViDA_Compliance_Validator.groovy"))

    def "Should certify valid EU ViDA tax master profile with German VAT and Peppol ICD"() {
        given:
        def msg = new Message()
        msg.setBody(JsonOutput.toJson([
            BusinessPartner: "BP-10004829", Country: "DE", EuVatNumber: "DE136695976",
            PeppolParticipantId: "0204:991-12345-67", StructuredInvoiceFormat: "XRECHNUNG_3.0",
            TaxClassificationCode: "1", VidaDigitalReportingReady: true
        ]))
        when:
        def res = script.processData(msg)
        def json = new JsonSlurper().parseText(res.getBody(String))
        then:
        res.getHeaders()["X-TAX-Gate-Status"] == "APPROVED"
        json.EuVidaDigitalReportingExtension.DigitalReportingClearance == "ACTIVE"
    }

    def "Should reject German VAT number failing ISO 7064 Mod-11,10 check-digit"() {
        given:
        def msg = new Message()
        msg.setBody(JsonOutput.toJson([
            BusinessPartner: "BP-99900112", Country: "DE", EuVatNumber: "DE136695970",
            PeppolParticipantId: "0204:991-12345-67", StructuredInvoiceFormat: "XRECHNUNG_3.0",
            TaxClassificationCode: "1", VidaDigitalReportingReady: true
        ]))
        when:
        def res = script.processData(msg)
        then:
        res.getHeaders()["X-TAX-Gate-Status"] == "REJECTED"
    }

    def "Should quarantine counterparty failing EU ViDA EN 16931 e-invoice syntax"() {
        given:
        def msg = new Message()
        msg.setBody(JsonOutput.toJson([
            BusinessPartner: "BP-77700112", Country: "DE", EuVatNumber: "DE136695976",
            PeppolParticipantId: "0204:991-12345-67", StructuredInvoiceFormat: "UNSTRUCTURED_PDF",
            TaxClassificationCode: "1", VidaDigitalReportingReady: false
        ]))
        when:
        def res = script.processData(msg)
        then:
        res.getHeaders()["X-TAX-Gate-Status"] == "REJECTED"
    }
}
