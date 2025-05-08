A simple Besu plugin to compare the result of the gas estimation of different clients

The client to query for `eth_estimateGas` can configured using the CLI option `--plugin-ceg-endpoints` a map of client identifiers and their RPC url, for example:
```
--plugin-ceg-endpoints=Besu25.4.1=http://localhost:8545/,Geth=http://10.0.64.138:8545/,BesuDev=http://10.0.0.58:8545
```

It listen to incoming pending transactions and queries each configured client, then compare the returned estimation and logs results, highlighting differences, then if the pending transaction is included in a block, it also compares the effective gas used with the esitmations and highlight if a client under estimated the gas.
