# Runtime sidecar schemas

The three local draft-07 profiles describe the version-1 attribution catalog,
release-runtime inventory and release bundle. Regeneration and semantic checks in
`release_runtime.py` / `release_bundle.py` additionally enforce unique IDs, complete
references/membership, safe paths, identity agreement and exact file bytes. JSON
Schema alone does not prove those relationships. A blocked observation cannot be
rendered as a passing capsule.

`cyclonedx/` contains unchanged official CycloneDX JSON **1.6** schemas, an explicit
supported format choice, not a claim to be the newest version. Source:
[CycloneDX specification at 55343ba19dee1785acf1ce9191540d5fd7b590db](https://github.com/CycloneDX/specification/tree/55343ba19dee1785acf1ce9191540d5fd7b590db/schema).
The upstream Apache license is preserved in `cyclonedx/LICENSE`; upstream file
copyright/license declarations remain intact.

| Official file | SHA-256 |
| --- | --- |
| `bom-1.6.schema.json` | `3e92dddbc30cf7f6a02b80f0942b1a4cfd4fb1c26f1dfc4310afa9d613cafb93` |
| `spdx.schema.json` | `baa9d3bd1ed57b6751b0887edead6b5063ff53ff7429cf85d476c6c94af0166e` |
| `jsf-0.82.schema.json` | `8bae002c25e723db7ee1f26afde680ae1a2b1a8f6b4b4b0fd65dc3becb090aae` |

Validation uses only the existing hash-pinned `tools/research/requirements.txt`
jsonschema stack, with an offline local registry for all schema references. Standard
library imports remain usable without that stack; invoking rendering/schema
validation requires the prepared interpreter. No schema download occurs in CI or
bundle verification. Inventory `uniqueItems` validation uses exact sorted key/value
tuples and a set for flat string-valued objects (including class records), avoiding
pairwise comparison of the large class array. Other shapes retain the stock Draft-07
comparison, including distinct boolean/number semantics. All schemas and validation
keywords remain enforced; verification still independently re-derives current inputs.
