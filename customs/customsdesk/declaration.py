"""Turn what the documents say into a draft declaration."""

from __future__ import annotations

import re
from typing import Optional

from .models import Classification, Declaration, Extraction, Item, Representation

# Delivery terms under which the invoice price excludes transport to the EU
# border, so freight and insurance have to be added to reach customs value.
TERMS_EXCLUDING_FREIGHT = {"EXW", "FCA", "FAS", "FOB"}


def normalise(description: str) -> str:
    return re.sub(r"[^a-z0-9]+", " ", description.lower()).strip()


def digits(code: str) -> str:
    return re.sub(r"\D", "", code or "")


def build_declaration(
    extraction: Extraction,
    classification: Optional[Classification],
    history: dict[str, str],
    representation: Representation,
    exchange_rate: Optional[float] = None,
) -> Declaration:
    inv, pl = extraction.invoice, extraction.packing_list
    suggestions = {s.line_index: s for s in (classification.suggestions if classification else [])}

    items: list[Item] = []
    for i, line in enumerate(inv.lines):
        # Packing lines are matched to invoice lines by position. A mismatch in
        # line count is raised by the consistency check, not papered over here.
        packing = pl.lines[i] if i < len(pl.lines) else None
        item = Item(
            description=line.description,
            quantity=line.quantity,
            unit=line.unit,
            origin_country=line.origin_country.upper(),
            invoice_value=line.line_total,
            packages=packing.packages if packing else 0,
            package_type=packing.package_type if packing else "",
            gross_kg=packing.gross_kg if packing else 0.0,
            net_kg=packing.net_kg if packing else 0.0,
        )

        known = history.get(normalise(line.description))
        suggestion = suggestions.get(i)
        if known:
            item.commodity_code = known
            item.code_source = "history"
            item.code_reasoning = "Previously confirmed by this office for the same importer and description."
            item.code_confidence = "high"
        elif suggestion:
            item.commodity_code = digits(suggestion.commodity_code)
            item.code_source = "suggested"
            item.code_reasoning = suggestion.reasoning
            item.code_confidence = suggestion.confidence
            item.code_alternatives = [digits(a) for a in suggestion.alternatives]
        elif line.hs_code_on_document:
            item.commodity_code = digits(line.hs_code_on_document)
            item.code_source = "document"
            item.code_reasoning = "Code printed on the supplier's document; not independently checked."
            item.code_confidence = "low"
        items.append(item)

    declaration = Declaration(
        representation=representation,
        importer=inv.buyer,
        exporter=inv.seller,
        invoice_number=inv.number,
        invoice_date=inv.date,
        currency=inv.currency.upper(),
        exchange_rate=exchange_rate,
        incoterm=inv.incoterm.upper(),
        incoterm_place=inv.incoterm_place,
        invoice_total=inv.total,
        freight=inv.freight,
        insurance=inv.insurance,
        total_packages=pl.total_packages,
        total_gross_kg=pl.total_gross_kg,
        total_net_kg=pl.total_net_kg,
        items=items,
    )
    compute_customs_values(declaration)
    return declaration


def compute_customs_values(declaration: Declaration) -> None:
    """Customs value per item in EUR, or None where it cannot be worked out.

    Starts from the invoice value (the transaction value). Where the delivery
    term excludes transport, freight and insurance are apportioned by gross
    weight. Other additions and deductions (commissions, royalties, assists)
    are not handled and remain the declarant's judgement.
    """
    d = declaration
    rate = 1.0 if d.currency == "EUR" else d.exchange_rate
    extra = 0.0
    if d.incoterm in TERMS_EXCLUDING_FREIGHT:
        extra = (d.freight or 0.0) + (d.insurance or 0.0)
    total_gross = sum(it.gross_kg for it in d.items)

    for it in d.items:
        if not rate:
            it.customs_value_eur = None
            continue
        share = it.gross_kg / total_gross if total_gross else 1 / len(d.items)
        it.customs_value_eur = round((it.invoice_value + extra * share) / rate, 2)
