"""Data model.

Two layers:

* ``Extraction`` is what is read off the documents. It is the schema the
  language model fills in, so every field is something visible on an invoice
  or packing list.
* ``Shipment`` is the working file for one declaration: the extraction, the
  draft declaration built from it, the flags raised by the checks, and the
  declarant's decisions.
"""

from __future__ import annotations

from enum import Enum
from typing import Literal, Optional

from pydantic import BaseModel, Field


# --------------------------------------------------------------------------
# What the documents say
# --------------------------------------------------------------------------


class Party(BaseModel):
    name: str = ""
    address: str = ""
    country: str = Field("", description="ISO 3166-1 alpha-2 country code")
    vat_number: str = Field("", description="VAT number including country prefix if shown")
    eori: str = Field("", description="EORI number if shown")


class InvoiceLine(BaseModel):
    description: str
    quantity: float
    unit: str = Field("", description="Unit of quantity as written, e.g. PCS, KG, M")
    unit_price: float
    line_total: float
    origin_country: str = Field("", description="ISO alpha-2 country of origin if stated")
    hs_code_on_document: str = Field("", description="Tariff code printed on the document, digits only")


class Invoice(BaseModel):
    number: str = ""
    date: str = Field("", description="ISO date YYYY-MM-DD")
    seller: Party = Party()
    buyer: Party = Party()
    currency: str = Field("", description="ISO 4217 code")
    incoterm: str = Field("", description="Incoterms code, e.g. FOB, CIF, EXW")
    incoterm_place: str = ""
    lines: list[InvoiceLine] = []
    total: float = 0.0
    freight: Optional[float] = Field(None, description="Freight charge if itemised on the invoice")
    insurance: Optional[float] = Field(None, description="Insurance charge if itemised on the invoice")


class PackingLine(BaseModel):
    description: str
    quantity: float
    packages: int = 0
    package_type: str = Field("", description="e.g. CARTONS, PALLETS")
    gross_kg: float = 0.0
    net_kg: float = 0.0


class PackingList(BaseModel):
    number: str = ""
    lines: list[PackingLine] = []
    total_packages: int = 0
    total_gross_kg: float = 0.0
    total_net_kg: float = 0.0


class Extraction(BaseModel):
    invoice: Invoice
    packing_list: PackingList
    reading_notes: list[str] = Field(
        default_factory=list,
        description="Anything illegible, ambiguous or contradictory in the documents",
    )


class CodeSuggestion(BaseModel):
    line_index: int = Field(description="Zero-based index of the invoice line")
    commodity_code: str = Field(description="Suggested tariff code, digits only, 8 or 10 digits")
    reasoning: str = Field(description="Why this heading and subheading apply")
    confidence: Literal["low", "medium", "high"]
    alternatives: list[str] = Field(default_factory=list, description="Other plausible codes")


class Classification(BaseModel):
    suggestions: list[CodeSuggestion]


# --------------------------------------------------------------------------
# The working file
# --------------------------------------------------------------------------


class Representation(str, Enum):
    DIRECT = "direct"
    INDIRECT = "indirect"


class Severity(str, Enum):
    INFO = "info"  # shown, never blocks approval
    WARNING = "warning"  # must be resolved before approval
    HIGH = "high"  # must be resolved before approval


class Resolution(str, Enum):
    CORRECTED = "corrected"
    IMPORTER_CONFIRMED = "importer_confirmed"
    GUARANTEE_TAKEN = "guarantee_taken"
    NOT_APPLICABLE = "not_applicable"


class Flag(BaseModel):
    id: str
    check: str
    severity: Severity
    title: str
    detail: str
    item_index: Optional[int] = None
    evidence: dict = {}
    resolution: Optional[Resolution] = None
    resolution_note: str = ""
    resolved_by: str = ""

    @property
    def blocking(self) -> bool:
        return self.severity != Severity.INFO and self.resolution is None


class Item(BaseModel):
    description: str
    quantity: float
    unit: str = ""
    origin_country: str = ""
    packages: int = 0
    package_type: str = ""
    gross_kg: float = 0.0
    net_kg: float = 0.0
    invoice_value: float = 0.0  # in invoice currency
    customs_value_eur: Optional[float] = None
    commodity_code: str = ""
    code_source: Literal["", "suggested", "history", "document", "declarant"] = ""
    code_reasoning: str = ""
    code_confidence: str = ""
    code_alternatives: list[str] = []
    code_confirmed: bool = False


class Declaration(BaseModel):
    procedure: str = "4000"  # release for free circulation
    representation: Representation = Representation.DIRECT
    importer: Party = Party()
    exporter: Party = Party()
    invoice_number: str = ""
    invoice_date: str = ""
    currency: str = ""
    exchange_rate: Optional[float] = None  # units of invoice currency per 1 EUR
    incoterm: str = ""
    incoterm_place: str = ""
    invoice_total: float = 0.0
    freight: Optional[float] = None
    insurance: Optional[float] = None
    total_packages: int = 0
    total_gross_kg: float = 0.0
    total_net_kg: float = 0.0
    items: list[Item] = []


class Status(str, Enum):
    DRAFT = "draft"
    APPROVED = "approved"
    DECLINED = "declined"


class Shipment(BaseModel):
    id: str
    created_at: str
    status: Status = Status.DRAFT
    extraction: Extraction
    declaration: Declaration
    flags: list[Flag] = []
    approved_by: str = ""
    decline_reason: str = ""

    def blocking_flags(self) -> list[Flag]:
        return [f for f in self.flags if f.blocking]

    def unconfirmed_items(self) -> list[int]:
        return [i for i, it in enumerate(self.declaration.items) if not it.code_confirmed]
