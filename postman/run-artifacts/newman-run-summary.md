# HarborSync Newman Run Summary

Run date: 2026-06-11

## Postman CLI

Installed user-level binary:

```bash
/tmp/harborsync-postman-cli/bin/postman --version
```

Observed version:

```text
1.39.0
```

Note: `/snap/bin/postman` exists on the machine, but it is the GUI snap package and failed inside this sandbox with read-only runtime directory errors. The working CLI binary is under `/tmp/harborsync-postman-cli/bin/`.

## Endpoint Test Collection

Command used during local verification:

```bash
/tmp/harborsync-newman/node_modules/.bin/newman run postman/HarborSync_Endpoint_Test_Collection.postman_collection.json -e postman/harborsync.local.postman_environment.json --reporters cli
```

Result:

```text
iterations:         1 executed, 0 failed
requests:          21 executed, 0 failed
test-scripts:      21 executed, 0 failed
prerequest-scripts: 22 executed, 0 failed
assertions:        23 executed, 0 failed
total duration:    9.7s
average response:  65ms
```

## Simulation Collection

Command used during local verification:

```bash
/tmp/harborsync-newman/node_modules/.bin/newman run postman/HarborSync_Simulation_Collection.postman_collection.json -e postman/harborsync.local.postman_environment.json --reporters cli
```

Result:

```text
iterations:         1 executed, 0 failed
requests:          10 executed, 0 failed
test-scripts:      10 executed, 0 failed
prerequest-scripts: 11 executed, 0 failed
assertions:        10 executed, 0 failed
total duration:    8.3s
average response:  18ms
```

## Saved Artifacts

- `postman/HarborSync_Endpoint_Test_Collection.postman_collection.json`
- `postman/HarborSync_Simulation_Collection.postman_collection.json`
- `postman/harborsync.local.postman_environment.json`
- `postman/run-artifacts/newman-run-summary.md`

The raw Newman JSON reports generated during verification were not committed because they include resolved Authorization headers. Use the commands above to reproduce the terminal output locally.
