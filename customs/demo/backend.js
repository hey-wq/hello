"use strict";
// In-browser stand-in for the Python server, for the hosted demo page only.
// It mirrors customsdesk/{declaration,checks,service}.py closely enough that
// the review screen behaves the same on the sample shipment. Nothing is saved
// or sent. demo/parity.py checks it against the real server.

(function () {
  const SAMPLE = window.DEMO_DATA.sample;
  const BENCHMARKS = window.DEMO_DATA.benchmarks;
  const EXCLUDING_FREIGHT = ["EXW", "FCA", "FAS", "FOB"];
  const shipments = new Map();
  const events = new Map();
  let counter = 0;

  const clone = (x) => JSON.parse(JSON.stringify(x));
  const digits = (s) => String(s || "").replace(/\D/g, "");
  const normalise = (s) => s.toLowerCase().replace(/[^a-z0-9]+/g, " ").trim();
  const close = (a, b) => Math.abs(a - b) <= Math.max(0.01, 0.005 * Math.max(Math.abs(a), Math.abs(b)));
  const round2 = (n) => Math.round(n * 100) / 100;
  const g = (n) => String(Number(n));
  const importerKey = (p) => (p.vat_number || p.eori || "").replace(/\s/g, "").toUpperCase() || normalise(p.name);

  class Refused extends Error {}

  function log(id, actor, type, payload) {
    events.get(id).push({ seq: events.get(id).length + 1, at: new Date().toISOString(), actor, type, payload, prev_hash: "", hash: "" });
  }

  function flag(check, severity, title, detail, itemIndex, evidence) {
    const has = itemIndex !== null && itemIndex !== undefined;
    return { id: check + (has ? ":" + itemIndex : ""), check, severity, title, detail, item_index: has ? itemIndex : null, evidence: evidence || {}, resolution: null, resolution_note: "", resolved_by: "" };
  }

  // ---- declaration.py ------------------------------------------------------

  function build(extraction, classification, history) {
    const inv = extraction.invoice, pl = extraction.packing_list;
    const suggestions = {};
    classification.suggestions.forEach((s) => (suggestions[s.line_index] = s));
    const items = inv.lines.map((line, i) => {
      const p = pl.lines[i];
      const item = {
        description: line.description, quantity: line.quantity, unit: line.unit, origin_country: line.origin_country.toUpperCase(),
        packages: p ? p.packages : 0, package_type: p ? p.package_type : "", gross_kg: p ? p.gross_kg : 0, net_kg: p ? p.net_kg : 0,
        invoice_value: line.line_total, customs_value_eur: null, commodity_code: "", code_source: "", code_reasoning: "",
        code_confidence: "", code_alternatives: [], code_confirmed: false,
      };
      const known = history[normalise(line.description)], s = suggestions[i];
      if (known) Object.assign(item, { commodity_code: known, code_source: "history", code_confidence: "high", code_reasoning: "Previously confirmed by this office for the same importer and description." });
      else if (s) Object.assign(item, { commodity_code: digits(s.commodity_code), code_source: "suggested", code_reasoning: s.reasoning, code_confidence: s.confidence, code_alternatives: s.alternatives.map(digits) });
      return item;
    });
    return {
      procedure: "4000", representation: "direct", importer: clone(inv.buyer), exporter: clone(inv.seller),
      invoice_number: inv.number, invoice_date: inv.date, currency: inv.currency.toUpperCase(), exchange_rate: null,
      incoterm: inv.incoterm.toUpperCase(), incoterm_place: inv.incoterm_place, invoice_total: inv.total,
      freight: inv.freight, insurance: inv.insurance, total_packages: pl.total_packages,
      total_gross_kg: pl.total_gross_kg, total_net_kg: pl.total_net_kg, items,
    };
  }

  function computeValues(d) {
    const rate = d.currency === "EUR" ? 1 : d.exchange_rate;
    const extra = EXCLUDING_FREIGHT.includes(d.incoterm) ? (d.freight || 0) + (d.insurance || 0) : 0;
    const totalGross = d.items.reduce((sum, it) => sum + it.gross_kg, 0);
    d.items.forEach((it) => {
      if (!rate) { it.customs_value_eur = null; return; }
      const share = totalGross ? it.gross_kg / totalGross : 1 / d.items.length;
      it.customs_value_eur = round2((it.invoice_value + extra * share) / rate);
    });
  }

  // ---- checks.py -----------------------------------------------------------

  function checkDocuments(x) {
    const inv = x.invoice, pl = x.packing_list, out = [];
    x.reading_notes.forEach((note, n) => out.push(flag("reading_note_" + n, "info", "Note from document reading", note)));
    if (inv.lines.length !== pl.lines.length)
      out.push(flag("line_count", "warning", "Invoice and packing list have different numbers of lines",
        `The invoice has ${inv.lines.length} lines and the packing list has ${pl.lines.length}. Weights and packages were matched by position and may be against the wrong goods.`,
        null, { invoice_lines: inv.lines.length, packing_lines: pl.lines.length }));
    inv.lines.forEach((line, i) => {
      const expected = line.quantity * line.unit_price;
      if (line.unit_price && !close(expected, line.line_total))
        out.push(flag("line_arithmetic", "warning", "Invoice line does not add up",
          `${g(line.quantity)} × ${g(line.unit_price)} = ${expected.toFixed(2)}, but the line total reads ${line.line_total.toFixed(2)}.`,
          i, { expected: round2(expected), stated: line.line_total }));
      if (i < pl.lines.length && !close(line.quantity, pl.lines[i].quantity))
        out.push(flag("quantity_mismatch", "warning", "Quantity differs between invoice and packing list",
          `Invoice: ${g(line.quantity)}. Packing list: ${g(pl.lines[i].quantity)}.`, i, { invoice: line.quantity, packing_list: pl.lines[i].quantity }));
    });
    const linesSum = inv.lines.reduce((s, l) => s + l.line_total, 0);
    const goods = inv.total - (inv.freight || 0) - (inv.insurance || 0);
    if (inv.lines.length && !(close(linesSum, inv.total) || close(linesSum, goods)))
      out.push(flag("invoice_total", "warning", "Invoice total does not match its lines",
        `The lines add up to ${linesSum.toFixed(2)}; the invoice total reads ${inv.total.toFixed(2)}.`, null, { lines_sum: round2(linesSum), stated: inv.total }));
    pl.lines.forEach((p, i) => {
      if (p.gross_kg > 0 && p.net_kg > p.gross_kg)
        out.push(flag("net_over_gross", "warning", "Net weight exceeds gross weight", `Net ${g(p.net_kg)} kg, gross ${g(p.gross_kg)} kg.`, i, { net_kg: p.net_kg, gross_kg: p.gross_kg }));
    });
    [["gross weight", pl.total_gross_kg, "gross_kg"], ["net weight", pl.total_net_kg, "net_kg"], ["packages", pl.total_packages, "packages"]].forEach(([label, stated, key]) => {
      const summed = pl.lines.reduce((s, p) => s + p[key], 0);
      if (stated && summed && !close(stated, summed))
        out.push(flag("packing_total_" + label.replace(" ", "_"), "warning", `Packing list total ${label} does not match its lines`,
          `The lines add up to ${g(summed)}; the total reads ${g(stated)}.`, null, { lines_sum: summed, stated }));
    });
    return out;
  }

  function checkCompleteness(d) {
    const out = [];
    if (!d.currency) out.push(flag("currency_missing", "warning", "Invoice currency not found", "Enter the currency."));
    else if (d.currency !== "EUR" && !d.exchange_rate)
      out.push(flag("exchange_rate_missing", "warning", "Exchange rate needed",
        `The invoice is in ${d.currency}. Enter the official customs exchange rate so customs values can be calculated in euro.`, null, { currency: d.currency }));
    if (!d.incoterm) out.push(flag("incoterm_missing", "warning", "Delivery terms not found", "Without the delivery terms it is unclear whether freight is included in the price."));
    else if (EXCLUDING_FREIGHT.includes(d.incoterm) && d.freight === null)
      out.push(flag("freight_missing", "warning", "Freight cost needed",
        `Under ${d.incoterm} the invoice price excludes transport to the EU border. Enter the freight (and insurance, if any) so it can be added to the customs value.`, null, { incoterm: d.incoterm }));
    if (!d.importer.eori) out.push(flag("importer_eori_missing", "warning", "Importer EORI number missing", "Enter the importer's EORI."));
    else if (!/^[A-Z]{2}[A-Z0-9]{1,15}$/.test(d.importer.eori.replace(/ /g, "").toUpperCase()))
      out.push(flag("importer_eori_format", "warning", "Importer EORI number is not in a valid format",
        `“${d.importer.eori}” should be a two-letter country code followed by up to 15 characters.`, null, { eori: d.importer.eori }));
    d.items.forEach((it, i) => {
      if (!it.origin_country) out.push(flag("origin_missing", "warning", "Country of origin not stated", "Enter the origin.", i));
      if (!it.net_kg) out.push(flag("net_weight_missing", "warning", "Net weight missing", "Enter the net weight.", i));
      if (!it.commodity_code) out.push(flag("code_missing", "warning", "No commodity code", "Enter the commodity code.", i));
      else if (it.commodity_code.length !== 10)
        out.push(flag("code_length", "warning", "Commodity code is not 10 digits",
          `“${it.commodity_code}” has ${it.commodity_code.length} digits. An import declaration needs the full 10-digit TARIC code; look up the subdivision and complete it.`, i, { code: it.commodity_code }));
    });
    return out;
  }

  function lookup(code, origin) {
    for (const length of [8, 6]) {
      const hit = BENCHMARKS.find((b) => b.code === code.slice(0, length) && b.origin === origin);
      if (hit && code.length >= length) return hit;
    }
    return null;
  }

  function checkValuation(d) {
    const out = [];
    d.items.forEach((it, i) => {
      if (!(it.customs_value_eur && it.net_kg && it.commodity_code && it.origin_country)) return;
      const unit = it.customs_value_eur / it.net_kg, ref = lookup(it.commodity_code, it.origin_country);
      if (ref && unit < ref.low)
        out.push(flag("value_below_reference", unit < 0.5 * ref.low ? "high" : "warning", "Declared value is below the reference range",
          `€${unit.toFixed(2)} per kg declared. The reference for code ${ref.code} from ${ref.origin} is €${ref.median.toFixed(2)} per kg, with a low mark of €${ref.low.toFixed(2)} (${ref.source}, ${ref.period}). Ask the importer for proof of the price paid.`,
          i, { declared_eur_per_kg: round2(unit), reference_median: ref.median, reference_low: ref.low, source: ref.source, period: ref.period }));
    });
    return out;
  }

  function checkTrader(d, known, valueFlags) {
    const out = [], vat = d.importer.vat_number.replace(/ /g, "").toUpperCase();
    if (vat) out.push(flag("vat_unchecked", "info", "Importer VAT number was not checked against VIES", "Online validation is switched off or the service did not answer.", null, { vat }));
    if (!known)
      out.push(valueFlags
        ? flag("new_importer_low_value", "high", "First shipment for this importer, with a low declared value",
            "This office has no approved declarations for this importer, and at least one item is below the reference range. Consider acting as direct representative, taking a guarantee, or declining the job.")
        : flag("new_importer", "info", "First shipment for this importer",
            "This office has no approved declarations for this importer. Check the mandate, company registration and who is behind the company."));
    return out;
  }

  // ---- service.py ----------------------------------------------------------

  const approvedFor = (key, excluding) => [...shipments.values()].some((s) => s.status === "approved" && s.id !== excluding && importerKey(s.declaration.importer) === key);

  function recheck(s) {
    const d = s.declaration;
    computeValues(d);
    const value = checkValuation(d);
    const fresh = checkDocuments(s.extraction).concat(checkCompleteness(d), value, checkTrader(d, approvedFor(importerKey(d.importer), s.id), value.length > 0));
    const before = {};
    s.flags.forEach((f) => (before[f.id] = f));
    const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
    fresh.forEach((f) => {
      const old = before[f.id];
      if (old && old.resolution && same(old.evidence, f.evidence)) Object.assign(f, { resolution: old.resolution, resolution_note: old.resolution_note, resolved_by: old.resolved_by });
      if (!old || !same(old.evidence, f.evidence)) log(s.id, "system", "flag_raised", { flag: f.id, severity: f.severity, title: f.title, detail: f.detail, evidence: f.evidence });
    });
    const after = new Set(fresh.map((f) => f.id));
    Object.keys(before).forEach((id) => { if (!after.has(id)) log(s.id, "system", "flag_cleared", { flag: id, title: before[id].title }); });
    s.flags = fresh;
  }

  function open(actor, id) {
    counter += 1;
    id = id || "demo" + counter;
    const extraction = clone(SAMPLE.extraction);
    const history = {};
    const key = importerKey(extraction.invoice.buyer);
    shipments.forEach((s) => {
      if (s.status === "approved" && importerKey(s.declaration.importer) === key)
        s.declaration.items.forEach((it) => (history[normalise(it.description)] = it.commodity_code));
    });
    const s = { id, created_at: new Date().toISOString(), status: "draft", extraction, declaration: build(extraction, SAMPLE.classification, history), flags: [], approved_by: "", decline_reason: "" };
    shipments.set(id, s);
    events.set(id, []);
    log(id, actor, "shipment_opened", { representation: "direct" });
    log(id, "system", "documents_read", { invoice_number: extraction.invoice.number, lines: extraction.invoice.lines.length, declared_total: extraction.invoice.total, currency: extraction.invoice.currency });
    s.declaration.items.forEach((it, i) => { if (it.commodity_code) log(id, "system", "code_proposed", { item: i, code: it.commodity_code, source: it.code_source, confidence: it.code_confidence }); });
    recheck(s);
    return s;
  }

  function editable(id) {
    const s = shipments.get(id);
    if (!s) throw new Refused("Shipment not found.");
    if (s.status !== "draft") throw new Refused(`This shipment is ${s.status} and can no longer be changed.`);
    return s;
  }

  const NUMERIC = ["exchange_rate", "freight", "insurance", "quantity", "packages", "gross_kg", "net_kg"];
  const UPPER = ["currency", "incoterm", "origin_country", "country"];
  const HEADER = ["currency", "exchange_rate", "incoterm", "incoterm_place", "freight", "insurance", "representation"];
  const IMPORTER = ["name", "vat_number", "eori", "country", "address"];
  const ITEM = ["description", "quantity", "unit", "origin_country", "packages", "gross_kg", "net_kg", "commodity_code"];

  function edit(id, body) {
    const s = editable(id), d = s.declaration, changes = [];
    const apply = (target, field, value, label, allowed) => {
      if (!allowed.includes(field)) throw new Refused(`“${field}” cannot be edited.`);
      if (field === "commodity_code") value = digits(value);
      if (UPPER.includes(field) && typeof value === "string") value = value.trim().toUpperCase();
      if (NUMERIC.includes(field) && value !== null) {
        if (typeof value !== "number" || !isFinite(value)) throw new Refused(`Invalid value for ${label}: enter a number.`);
      }
      if (field === "representation" && !["direct", "indirect"].includes(value)) throw new Refused("Invalid value for representation.");
      if (target[field] !== value) { changes.push({ field: label, old: target[field], new: value }); target[field] = value; }
    };
    Object.entries(body.header || {}).forEach(([f, v]) => apply(d, f, v, f, HEADER));
    Object.entries(body.importer || {}).forEach(([f, v]) => apply(d.importer, f, v, "importer." + f, IMPORTER));
    Object.entries(body.items || {}).forEach(([index, fields]) => {
      const item = d.items[Number(index)];
      if (!item) throw new Refused(`There is no item ${Number(index) + 1}.`);
      Object.entries(fields).forEach(([f, v]) => {
        const codeBefore = item.commodity_code;
        apply(item, f, v, `item ${Number(index) + 1}.${f}`, ITEM);
        if (f === "commodity_code" && item.commodity_code !== codeBefore) {
          if (!(codeBefore && item.commodity_code.startsWith(codeBefore))) Object.assign(item, { code_source: "declarant", code_reasoning: "", code_confidence: "", code_alternatives: [] });
          item.code_confirmed = false;
        }
      });
    });
    changes.forEach((c) => log(id, body.actor, "field_changed", c));
    recheck(s);
    return s;
  }

  function confirm(id, index, actor) {
    const s = editable(id), item = s.declaration.items[index];
    if (!item) throw new Refused(`There is no item ${index + 1}.`);
    if (item.commodity_code.length !== 10) throw new Refused("Complete the 10-digit commodity code before confirming it.");
    item.code_confirmed = true;
    log(id, actor, "code_confirmed", { item: index, code: item.commodity_code, source: item.code_source });
    return s;
  }

  function resolve(id, flagId, body) {
    const s = editable(id), f = s.flags.find((x) => x.id === flagId);
    if (!f) throw new Refused("That flag no longer exists.");
    if (f.severity === "info") throw new Refused("Information notes do not need resolving.");
    const note = (body.note || "").trim();
    if (note.length < 10) throw new Refused("Describe what was checked or received, in a sentence or more.");
    Object.assign(f, { resolution: body.resolution, resolution_note: note, resolved_by: body.actor });
    log(id, body.actor, "flag_resolved", { flag: flagId, title: f.title, resolution: body.resolution, note });
    return s;
  }

  const blocking = (s) => s.flags.filter((f) => f.severity !== "info" && !f.resolution);
  const unconfirmed = (s) => s.declaration.items.map((it, i) => (it.code_confirmed ? -1 : i)).filter((i) => i >= 0);

  function approve(id, actor) {
    const s = editable(id);
    if (blocking(s).length) throw new Refused(`${blocking(s).length} flag(s) still need a recorded resolution.`);
    if (unconfirmed(s).length) throw new Refused(`${unconfirmed(s).length} commodity code(s) have not been confirmed.`);
    s.status = "approved";
    s.approved_by = actor;
    log(id, actor, "declaration_approved", { items: s.declaration.items.length, customs_value_eur: s.declaration.items.reduce((sum, it) => sum + (it.customs_value_eur || 0), 0) });
    return s;
  }

  function decline(id, body) {
    const s = editable(id), reason = (body.reason || "").trim();
    if (reason.length < 10) throw new Refused("Give the reason for declining, in a sentence or more.");
    s.status = "declined";
    s.decline_reason = reason;
    log(id, body.actor, "job_declined", { reason });
    return s;
  }

  const view = (s) => ({ shipment: clone(s), blocking_flags: blocking(s).length, unconfirmed_items: unconfirmed(s), events: clone(events.get(s.id)), record_intact: true });

  function handle(method, path, body) {
    let m;
    if (path === "/api/config") return { reading_configured: false, benchmarks_are_sample: true };
    if (path === "/api/sample") return view(open(body.actor));
    if (path === "/api/shipments" && method === "GET")
      return [...shipments.values()].reverse().map((s) => ({ id: s.id, created_at: s.created_at, status: s.status, importer: s.declaration.importer.name,
        exporter: s.declaration.exporter.name, invoice_number: s.declaration.invoice_number, items: s.declaration.items.length, blocking_flags: blocking(s).length }));
    if ((m = path.match(/^\/api\/shipments\/([\w-]+)$/))) {
      if (method === "PATCH") return view(edit(m[1], body));
      if (!shipments.has(m[1])) throw new Refused("Shipment not found.");
      return view(shipments.get(m[1]));
    }
    if ((m = path.match(/^\/api\/shipments\/([\w-]+)\/items\/(\d+)\/confirm$/))) return view(confirm(m[1], Number(m[2]), body.actor));
    if ((m = path.match(/^\/api\/shipments\/([\w-]+)\/flags\/([^/]+)\/resolve$/))) return view(resolve(m[1], decodeURIComponent(m[2]), body));
    if ((m = path.match(/^\/api\/shipments\/([\w-]+)\/approve$/))) return view(approve(m[1], body.actor));
    if ((m = path.match(/^\/api\/shipments\/([\w-]+)\/decline$/))) return view(decline(m[1], body));
    throw new Refused("Not available in the demo.");
  }

  window.fetch = async function (path, options) {
    options = options || {};
    try {
      const body = typeof options.body === "string" ? JSON.parse(options.body) : {};
      return new Response(JSON.stringify(handle(options.method || "GET", path, body)), { status: 200, headers: { "Content-Type": "application/json" } });
    } catch (error) {
      if (!(error instanceof Refused)) throw error;
      return new Response(JSON.stringify({ detail: error.message }), { status: 409, headers: { "Content-Type": "application/json" } });
    }
  };

  open("Demo declarant", "demo");
})();
