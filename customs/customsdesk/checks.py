"""Checks run on every draft before a declarant sees it.

Each check returns flags. A flag with severity ``warning`` or ``high`` blocks
approval until the declarant records how it was dealt with, so the file never
shows a problem that was noticed and then ignored.
"""

from __future__ import annotations

import csv
import re
import statistics
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Optional

from .declaration import TERMS_EXCLUDING_FREIGHT
from .models import Declaration, Extraction, Flag, Severity

REL_TOLERANCE = 0.005
MIN_HISTORY_SAMPLES = 5


def _close(a: float, b: float) -> bool:
    return abs(a - b) <= max(0.01, REL_TOLERANCE * max(abs(a), abs(b)))


def _flag(check, severity, title, detail, item_index=None, **evidence) -> Flag:
    suffix = "" if item_index is None else f":{item_index}"
    return Flag(
        id=f"{check}{suffix}",
        check=check,
        severity=severity,
        title=title,
        detail=detail,
        item_index=item_index,
        evidence=evidence,
    )


# --------------------------------------------------------------------------
# Documents agree with themselves and each other
# --------------------------------------------------------------------------


def check_documents(extraction: Extraction) -> list[Flag]:
    inv, pl = extraction.invoice, extraction.packing_list
    flags: list[Flag] = []

    for n, note in enumerate(extraction.reading_notes):
        flags.append(_flag(f"reading_note_{n}", Severity.INFO, "Note from document reading", note))

    if len(inv.lines) != len(pl.lines):
        flags.append(
            _flag(
                "line_count",
                Severity.WARNING,
                "Invoice and packing list have different numbers of lines",
                f"The invoice has {len(inv.lines)} lines and the packing list has {len(pl.lines)}. "
                "Weights and packages were matched by position and may be against the wrong goods.",
                invoice_lines=len(inv.lines),
                packing_lines=len(pl.lines),
            )
        )

    for i, line in enumerate(inv.lines):
        expected = line.quantity * line.unit_price
        if line.unit_price and not _close(expected, line.line_total):
            flags.append(
                _flag(
                    "line_arithmetic",
                    Severity.WARNING,
                    "Invoice line does not add up",
                    f"{line.quantity:g} × {line.unit_price:g} = {expected:.2f}, "
                    f"but the line total reads {line.line_total:.2f}.",
                    i,
                    expected=round(expected, 2),
                    stated=line.line_total,
                )
            )
        if i < len(pl.lines) and not _close(line.quantity, pl.lines[i].quantity):
            flags.append(
                _flag(
                    "quantity_mismatch",
                    Severity.WARNING,
                    "Quantity differs between invoice and packing list",
                    f"Invoice: {line.quantity:g}. Packing list: {pl.lines[i].quantity:g}.",
                    i,
                    invoice=line.quantity,
                    packing_list=pl.lines[i].quantity,
                )
            )

    lines_sum = sum(line.line_total for line in inv.lines)
    stated_goods = inv.total - (inv.freight or 0.0) - (inv.insurance or 0.0)
    if inv.lines and not (_close(lines_sum, inv.total) or _close(lines_sum, stated_goods)):
        flags.append(
            _flag(
                "invoice_total",
                Severity.WARNING,
                "Invoice total does not match its lines",
                f"The lines add up to {lines_sum:.2f}; the invoice total reads {inv.total:.2f}.",
                lines_sum=round(lines_sum, 2),
                stated=inv.total,
            )
        )

    for i, p in enumerate(pl.lines):
        if p.net_kg > p.gross_kg > 0:
            flags.append(
                _flag(
                    "net_over_gross",
                    Severity.WARNING,
                    "Net weight exceeds gross weight",
                    f"Net {p.net_kg:g} kg, gross {p.gross_kg:g} kg.",
                    i,
                    net_kg=p.net_kg,
                    gross_kg=p.gross_kg,
                )
            )

    for label, stated, summed in (
        ("gross weight", pl.total_gross_kg, sum(p.gross_kg for p in pl.lines)),
        ("net weight", pl.total_net_kg, sum(p.net_kg for p in pl.lines)),
        ("packages", pl.total_packages, sum(p.packages for p in pl.lines)),
    ):
        if stated and summed and not _close(stated, summed):
            flags.append(
                _flag(
                    f"packing_total_{label.replace(' ', '_')}",
                    Severity.WARNING,
                    f"Packing list total {label} does not match its lines",
                    f"The lines add up to {summed:g}; the total reads {stated:g}.",
                    lines_sum=summed,
                    stated=stated,
                )
            )
    return flags


def escalation_reasons(extraction: Extraction) -> list[str]:
    """Why a first reading should not be trusted and the documents read again.

    Combines what the reader admitted (legibility, notes) with what can be
    verified independently (required fields present, figures that add up),
    because a reader that misreads a digit does not always know it did.
    """
    inv = extraction.invoice
    reasons: list[str] = []
    if extraction.legibility != "clear":
        reasons.append(f"reader reported the documents as {extraction.legibility.replace('_', ' ')}")
    if extraction.reading_notes:
        reasons.append(f"reader left {len(extraction.reading_notes)} note(s) about unclear content")
    missing = [
        label
        for label, value in (
            ("invoice number", inv.number),
            ("currency", inv.currency),
            ("invoice total", inv.total),
            ("buyer name", inv.buyer.name),
            ("invoice lines", inv.lines),
            ("packing list lines", extraction.packing_list.lines),
        )
        if not value
    ]
    if missing:
        reasons.append("not found: " + ", ".join(missing))
    inconsistent = sorted({f.title for f in check_documents(extraction) if f.severity != Severity.INFO})
    reasons.extend(title[0].lower() + title[1:] for title in inconsistent)
    return reasons


# --------------------------------------------------------------------------
# The declaration is complete enough to file
# --------------------------------------------------------------------------


def check_completeness(d: Declaration) -> list[Flag]:
    flags: list[Flag] = []
    if not d.currency:
        flags.append(_flag("currency_missing", Severity.WARNING, "Invoice currency not found", "Enter the currency."))
    elif d.currency != "EUR" and not d.exchange_rate:
        flags.append(
            _flag(
                "exchange_rate_missing",
                Severity.WARNING,
                "Exchange rate needed",
                f"The invoice is in {d.currency}. Enter the official customs exchange rate "
                "so customs values can be calculated in euro.",
                currency=d.currency,
            )
        )
    if not d.incoterm:
        flags.append(
            _flag(
                "incoterm_missing",
                Severity.WARNING,
                "Delivery terms not found",
                "Without the delivery terms it is unclear whether freight is included in the price.",
            )
        )
    elif d.incoterm in TERMS_EXCLUDING_FREIGHT and d.freight is None:
        flags.append(
            _flag(
                "freight_missing",
                Severity.WARNING,
                "Freight cost needed",
                f"Under {d.incoterm} the invoice price excludes transport to the EU border. "
                "Enter the freight (and insurance, if any) so it can be added to the customs value.",
                incoterm=d.incoterm,
            )
        )

    if not d.importer.eori:
        flags.append(
            _flag("importer_eori_missing", Severity.WARNING, "Importer EORI number missing", "Enter the importer's EORI.")
        )
    elif not re.fullmatch(r"[A-Z]{2}[A-Z0-9]{1,15}", d.importer.eori.replace(" ", "").upper()):
        flags.append(
            _flag(
                "importer_eori_format",
                Severity.WARNING,
                "Importer EORI number is not in a valid format",
                f"“{d.importer.eori}” should be a two-letter country code followed by up to 15 characters.",
                eori=d.importer.eori,
            )
        )

    for i, it in enumerate(d.items):
        if not it.origin_country:
            flags.append(
                _flag("origin_missing", Severity.WARNING, "Country of origin not stated", "Enter the origin.", i)
            )
        if not it.net_kg:
            flags.append(_flag("net_weight_missing", Severity.WARNING, "Net weight missing", "Enter the net weight.", i))
        if not it.commodity_code:
            flags.append(
                _flag("code_missing", Severity.WARNING, "No commodity code", "Enter the commodity code.", i)
            )
        elif len(it.commodity_code) != 10:
            flags.append(
                _flag(
                    "code_length",
                    Severity.WARNING,
                    "Commodity code is not 10 digits",
                    f"“{it.commodity_code}” has {len(it.commodity_code)} digits. An import declaration needs "
                    "the full 10-digit TARIC code; look up the subdivision and complete it.",
                    i,
                    code=it.commodity_code,
                )
            )
    return flags


# --------------------------------------------------------------------------
# Declared value is plausible
# --------------------------------------------------------------------------


@dataclass
class Benchmark:
    code: str
    origin: str
    median: float
    low: float
    source: str
    period: str


class Benchmarks:
    """Reference unit values in EUR per kg of net weight, by tariff code and origin.

    Loaded from a CSV with columns: code, origin, median_eur_per_kg,
    low_eur_per_kg, source, period. ``code`` may be 8 or 6 digits; the most
    specific matching row is used.
    """

    def __init__(self, rows: list[Benchmark]):
        self._rows = {(r.code, r.origin): r for r in rows}

    @classmethod
    def from_csv(cls, path: Path) -> "Benchmarks":
        with open(path, newline="", encoding="utf-8") as fh:
            rows = [
                Benchmark(
                    code=r["code"].strip(),
                    origin=r["origin"].strip().upper(),
                    median=float(r["median_eur_per_kg"]),
                    low=float(r["low_eur_per_kg"]),
                    source=r["source"].strip(),
                    period=r["period"].strip(),
                )
                for r in csv.DictReader(fh)
            ]
        return cls(rows)

    def lookup(self, code: str, origin: str) -> Optional[Benchmark]:
        for length in (8, 6):
            hit = self._rows.get((code[:length], origin))
            if hit and len(code) >= length:
                return hit
        return None


# Returns past unit values (EUR/kg) this office has approved for a code and origin.
HistoryLookup = Callable[[str, str], list[float]]


def check_valuation(d: Declaration, benchmarks: Benchmarks, history: HistoryLookup) -> list[Flag]:
    flags: list[Flag] = []
    for i, it in enumerate(d.items):
        if not (it.customs_value_eur and it.net_kg and it.commodity_code and it.origin_country):
            continue
        unit = it.customs_value_eur / it.net_kg

        ref = benchmarks.lookup(it.commodity_code, it.origin_country)
        if ref and unit < ref.low:
            severity = Severity.HIGH if unit < 0.5 * ref.low else Severity.WARNING
            flags.append(
                _flag(
                    "value_below_reference",
                    severity,
                    "Declared value is below the reference range",
                    f"€{unit:.2f} per kg declared. The reference for code {ref.code} from "
                    f"{ref.origin} is €{ref.median:.2f} per kg, with a low mark of €{ref.low:.2f} "
                    f"({ref.source}, {ref.period}). Ask the importer for proof of the price paid.",
                    i,
                    declared_eur_per_kg=round(unit, 2),
                    reference_median=ref.median,
                    reference_low=ref.low,
                    source=ref.source,
                    period=ref.period,
                )
            )

        past = history(it.commodity_code[:8], it.origin_country)
        if len(past) >= MIN_HISTORY_SAMPLES:
            median = statistics.median(past)
            if unit < 0.5 * median:
                flags.append(
                    _flag(
                        "value_below_own_history",
                        Severity.WARNING,
                        "Declared value is well below this office's past declarations",
                        f"€{unit:.2f} per kg declared. The median across {len(past)} earlier approved "
                        f"declarations of the same code and origin is €{median:.2f} per kg.",
                        i,
                        declared_eur_per_kg=round(unit, 2),
                        own_median=round(median, 2),
                        samples=len(past),
                    )
                )
    return flags


# --------------------------------------------------------------------------
# The importer
# --------------------------------------------------------------------------

# Returns True/False for a VAT number, or None if it could not be checked.
VatCheck = Callable[[str], Optional[bool]]


def check_trader(d: Declaration, known_importer: bool, vat_check: VatCheck, value_flags: bool) -> list[Flag]:
    flags: list[Flag] = []
    vat = d.importer.vat_number.replace(" ", "").upper()
    if vat:
        valid = vat_check(vat)
        if valid is False:
            flags.append(
                _flag(
                    "vat_invalid",
                    Severity.HIGH,
                    "Importer VAT number is not valid in VIES",
                    f"{vat} was rejected by the EU VAT validation service.",
                    vat=vat,
                )
            )
        elif valid is None:
            flags.append(
                _flag(
                    "vat_unchecked",
                    Severity.INFO,
                    "Importer VAT number was not checked against VIES",
                    "Online validation is switched off or the service did not answer.",
                    vat=vat,
                )
            )

    if not known_importer:
        if value_flags:
            flags.append(
                _flag(
                    "new_importer_low_value",
                    Severity.HIGH,
                    "First shipment for this importer, with a low declared value",
                    "This office has no approved declarations for this importer, and at least one item is "
                    "below the reference range. Consider acting as direct representative, taking a "
                    "guarantee, or declining the job.",
                )
            )
        else:
            flags.append(
                _flag(
                    "new_importer",
                    Severity.INFO,
                    "First shipment for this importer",
                    "This office has no approved declarations for this importer. Check the mandate, "
                    "company registration and who is behind the company.",
                )
            )
    return flags


def run_all(
    extraction: Extraction,
    declaration: Declaration,
    benchmarks: Benchmarks,
    history: HistoryLookup,
    known_importer: bool,
    vat_check: VatCheck,
) -> list[Flag]:
    flags = check_documents(extraction) + check_completeness(declaration)
    value_flags = check_valuation(declaration, benchmarks, history)
    flags += value_flags
    flags += check_trader(declaration, known_importer, vat_check, bool(value_flags))
    return flags


def carry_over(old: list[Flag], new: list[Flag]) -> list[Flag]:
    """Keep the declarant's resolution on a flag whose evidence has not changed."""
    previous = {f.id: f for f in old}
    for f in new:
        before = previous.get(f.id)
        if before and before.resolution and before.evidence == f.evidence:
            f.resolution = before.resolution
            f.resolution_note = before.resolution_note
            f.resolved_by = before.resolved_by
    return new
