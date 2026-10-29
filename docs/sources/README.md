# Document sources

Editable sources of generated documents in `docs/`.

| Source | Generated file |
|---|---|
| `OnlineCasinoSimulator_ImplementationPlan_v1.1.html` | `../OnlineCasinoSimulator_ImplementationPlan_v1.1.pdf` |

The original plan (version 1.0, `../OnlineCasinoSimulator_ImplementationPlan.pdf`) was created in
Google Docs and is kept unchanged.

## Regenerating the PDF

From the repository root, run headless Chrome or Edge (A4 page size and margins come from the
HTML `@page` rule):

```bash
chrome --headless=new --no-pdf-header-footer \
  --print-to-pdf=docs/OnlineCasinoSimulator_ImplementationPlan_v1.1.pdf \
  docs/sources/OnlineCasinoSimulator_ImplementationPlan_v1.1.html
```

For a new document version, copy the HTML with a new version suffix, update the version in the
title block and in "Приложение А", and describe the changes in a proposal under `docs/proposals/`.
