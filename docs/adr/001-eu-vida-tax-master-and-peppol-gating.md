# ADR-001: Pre-Ingestion EU ViDA Tax Master & Peppol Governance Gate for S/4HANA 2025

## Status
Accepted

## Context
EU VAT in the Digital Age (ViDA Directive 2025/516) mandates EN 16931 structured e-invoicing and real-time Digital Reporting Requirements (DRR). Unvalidated tax numbers or unregistered Peppol participant IDs in S/4HANA 2025 cause tax filing rejections and blocked invoice runs.

## Decision
Implement a Groovy 3 Reader-streamed validation gate in SAP CPI enforcing ISO 7064 Mod-11,10 EU VIES VAT check-digit verification, ISO/IEC 6523 Peppol ICD validation, EN 16931 syntax checks, and SHA-256 compliance hashing.

## Consequences
Non-compliant tax master records are quarantined via HTTP 422 before polluting S/4HANA A_BusinessPartnerTaxNumber tables.
