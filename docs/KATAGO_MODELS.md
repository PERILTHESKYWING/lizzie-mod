# KataGo networks: catalogue and updates

Engine, network and backend are separate:

| Concern | Where it lives |
| --- | --- |
| KataGo executable and version | `katago-assets.json` → `assets` (per platform/backend, SHA-256 pinned); the version is read from the running engine |
| Neural-network files | `katago-assets.json` → `models`, the katagotraining.org refresh, and the optional user catalogue below |
| Backend (CUDA, TensorRT, OpenCL, Eigen, Metal) | chosen by KataGo Auto Setup per engine build; a network row may list the backends it was tested with |
| Selection | the engine profile's `-model` argument, written by Auto Setup when a network is activated |

## Getting a newer network

1. Open **KataGo Auto Setup**. The network list combines the built-in rows with the current
   official networks from katagotraining.org (strongest-network API and network table), refreshed
   every time the list is loaded.
2. Pick a row and download it. The file goes into `weights/` under its own name and is verified
   against the listed size and SHA-256 before use; a mismatch is refused and the file is not
   installed. Existing networks are never deleted or overwritten by a different network.
3. Activate it. If the switch fails, Auto Setup reports the failure and keeps the last committed
   setup as the selected one; the previous network file is untouched.
4. To go back, select the previously used network in the same list: every downloaded or imported
   network stays installed until you delete it yourself.
5. A network file from elsewhere can be imported with the existing **Import custom weight** action.

A row whose `minimumKataGoVersion` is newer than the detected engine is reported as incompatible
instead of being activated. Newer networks are not automatically better for every machine: larger
networks are slower, so choose by the analysis speed you need.

## Adding networks without a code change: `katago-models.json`

Create `katago-models.json` in the work directory (next to `config.txt`) to list networks the
built-in catalogue does not know yet, with pinned integrity data:

```json
{
  "schemaVersion": 1,
  "models": [
    {
      "id": "kata1-b28-2027",
      "displayName": "kata1 b28 (2027)",
      "downloadUrl": "https://media.katagotraining.org/uploaded/networks/models/kata1/<file>.bin.gz",
      "fileName": "<file>.bin.gz",
      "sha256": "<64 hex digits>",
      "sizeBytes": 123456789,
      "minimumKataGoVersion": "1.16.0",
      "architecture": "b28c512nbt",
      "publishedAt": "2027-01-01",
      "backends": ["cuda", "tensorrt", "opencl", "eigen"]
    }
  ]
}
```

Rules (`KataGoUserModelCatalog`):

- HTTPS only, and only official hosts (`media.katagotraining.org`, `katagotraining.org`,
  `github.com` release downloads); `sha256` and `sizeBytes` are required.
- `fileName` must be a plain `.bin.gz`/`.txt.gz`/`.bin` name equal to the URL's last segment, so a
  download can only land in `weights/`.
- Rows whose file is already in the built-in catalogue keep the built-in metadata.
- Invalid entries are skipped with a reason in `logs/app.log`; a broken file never hides the
  official list.

Maintainers who want a network to ship with the application add it to
`src/main/resources/katago-assets.json` instead (validated by `KataGoAssetCatalog`).
