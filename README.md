<p align="center">
  <img src="assets/logo.jpg" width="120" alt="VH Wallet Logo"/>
</p>

<h1 align="center">VH Wallet — Quai Android Integration</h1>

<p align="center">
  <strong>by <a href="https://vertexhivetech.com">VertexHiveTech</a></strong><br/>
  The first open Kotlin/Android SDK for building on Quai Network
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-brightgreen?style=flat-square"/>
  <img src="https://img.shields.io/badge/Language-Kotlin-blueviolet?style=flat-square"/>
  <img src="https://img.shields.io/badge/Network-Quai-cyan?style=flat-square"/>
  <img src="https://img.shields.io/badge/License-MIT-blue?style=flat-square"/>
</p>

---

> This SDK was built as part of **VH Wallet** — a multi-chain Android wallet by [VertexHiveTech](https://vertexhivetech.com). We open-sourced our Quai Network integration to help other Android builders avoid the same pain we went through. If this saves you time, give it a ⭐.

---

## Why This Exists

Quai Network is EVM-compatible at the address level, but **its transaction encoding is not standard RLP** — it uses Protobuf. This means:

- `web3j` cannot sign or broadcast Quai transactions natively
- You cannot just swap in an RPC URL like you would for Ethereum
- Address validity is zone-specific — not every `0x...` address is a valid QUAI address

This SDK solves all three problems for Android/Kotlin developers.

---

## How It Works

```
Your Android App (Kotlin)
        │
        ├── QuaiWalletManager   →  BIP-44 address derivation + validation
        ├── QuaiTransactionScanner →  Fetch received txs via Quaiscan API
        └── QuaiTransactionSender  →  Sends tx params to signing service
                                              │
                                    ┌─────────▼──────────┐
                                    │  quai-service       │
                                    │  (Node.js/Express)  │
                                    │  Hosted on Render   │
                                    │                     │
                                    │  Uses quais.js for  │
                                    │  Protobuf encoding  │
                                    └─────────┬──────────┘
                                              │
                                    Quai Network RPC
                                    (Cyprus1 zone)
```

The signing service handles Quai's custom Protobuf transaction encoding using the official `quais.js` SDK, then broadcasts to the network. Your private key is derived on-device and sent only to the signing service over HTTPS — **you should self-host this service in production.**

---

## Signing Service

The transaction signing service is a lightweight Node.js/Express app that handles Quai's Protobuf encoding.

**Public instance (for testing only):**
```
https://quai-service.onrender.com
```

| Endpoint | Method | Description |
|---|---|---|
| `/` | GET | Health check — `{"status":"Quai Service is running!"}` |
| `/sign-transaction` | POST | Sign and broadcast a transaction |

> ⚠️ **The public instance is for development and testing only.** For production, self-host your own instance. The full signing service source code is in the [`quai-service/`](quai-service/) folder of this repo.

### Self-hosting on Render (Free tier)

1. Fork this repo
2. Create a new **Web Service**
3. Point it to the `quai-service/` folder
4. Set **Build Command:** `npm install`
5. Set **Start Command:** `node index.js`
6. Deploy — done

---

## Quick Start

### 1. Add dependencies to `build.gradle`

```groovy
dependencies {
    // BIP-44 key derivation
    implementation 'org.web3j:core:4.9.4'
    implementation 'org.bitcoinj:bitcoinj-core:0.15.10'

    // HTTP (RPC calls + signing service)
    implementation 'com.squareup.okhttp3:okhttp:4.11.0'

    // Coroutines
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3'
}
```

### 2. Copy the SDK files into your project

```
app/src/main/kotlin/com/quai/sdk/
├── QuaiWalletManager.kt        ← Address generation + validation
├── QuaiTransactionSender.kt    ← Send QUAI transactions
├── QuaiTransactionScanner.kt   ← Fetch received transactions
└── QuaiJavaScriptBridge.kt     ← Alternative: send via quais.js WebView
```

---

## Usage

### Generate a Quai Address

```kotlin
val account = QuaiWalletManager.generateAddress(mnemonic, accountIndex = 0)

println(account.address)      // 0x000... (valid Cyprus1)
println(account.changeIndex)  // The change index where the valid address was found
```

> **Why scan change indices?** Quai address validity is determined by the address prefix. BIP-44 derivation doesn't guarantee a valid prefix, so we increment the change index until we find one that falls in the Cyprus1 QUAI range (`0x000–0x003`).

### Validate an Address

```kotlin
QuaiWalletManager.isValidCyprus1Address("0x001f...")  // true  — QUAI ledger
QuaiWalletManager.isValidCyprus1Address("0x00a7...")  // false — QI ledger, reject
QuaiWalletManager.isValidCyprus1Address("0xABCD")     // false — wrong format
```

**Address prefix rules:**

| Prefix value | Ledger | Valid for QUAI? |
|---|---|---|
| `0x000` – `0x003` | QUAI | ✅ Yes |
| `0x004` and above | QI | ❌ No — will fail |

### Send a Transaction

```kotlin
val sender = QuaiTransactionSender(isTestnet = false)

val txHash = sender.sendTransaction(
    mnemonic     = mnemonic,
    accountIndex = 0,
    toAddress    = "0x0002abcd...",
    amountInQuai = 1.5
)

println("Sent: $txHash")
```

### Send with Memo (Optional)

```kotlin
val memo    = "my-app-tag"
val memoHex = "0x" + memo.toByteArray().joinToString("") { "%02x".format(it) }

val txHash = sender.sendTransaction(
    mnemonic     = mnemonic,
    accountIndex = 0,
    toAddress    = "0x0002abcd...",
    amountInQuai = 0.5,
    memoHex      = memoHex
)
```

### Fetch Received Transactions

```kotlin
val scanner = QuaiTransactionScanner(isTestnet = false)
val txs     = scanner.fetchReceivedTransactions("0x000abc...", limit = 10)

txs.forEach { tx ->
    println("${tx.amountInQuai} QUAI from ${tx.fromAddress}")
    println("Explorer: ${tx.explorerUrl}")
}
```

> **Critical parsing note:** Quaiscan returns transaction values as **decimal strings**, not hex. Always parse with `BigInteger(value, 10)`, never `parseInt(value, 16)`.

### Estimate Gas Fee

```kotlin
val fee = sender.estimateGasFee()
println("Estimated fee: $fee QUAI")
```

### Testnet (Orchard)

```kotlin
// Just pass isTestnet = true — everything else is identical
val sender  = QuaiTransactionSender(isTestnet = true)
val scanner = QuaiTransactionScanner(isTestnet = true)
```

---

## Network Reference

| | Mainnet | Testnet (Orchard) |
|---|---|---|
| **RPC** | `https://rpc.quai.network/cyprus1` | `https://rpc.orchard.quai.network/cyprus1` |
| **Explorer** | `https://cyprus1.colosseum.quaiscan.io` | `https://orchard.quaiscan.io` |
| **Chain ID** | `9000` | varies |

---

## Key Gotchas for Quai Builders

These are the things that cost us the most time — learn from them:

1. **web3j cannot sign Quai transactions.** Quai uses Protobuf encoding, not RLP. Use `quais.js` for signing (this SDK handles it via the signing service).

2. **Not all 0x addresses are valid.** Only addresses with prefix `0x000–0x003` belong to Cyprus1 QUAI ledger. Addresses with `0x004+` are QI ledger and will be rejected.

3. **Quaiscan values are decimal, not hex.** The `value` field in transaction responses is a base-10 string. Parsing it as hex will give you a completely wrong amount.

4. **Timestamps are in seconds.** Multiply by 1000 for milliseconds when working with Android `Date` or `System.currentTimeMillis()`.

5. **BIP-44 path is `m/44'/994'/account'/0/change`.** The coin type for Quai is `994`. You must scan the change index to find a valid Cyprus1 address.

6. **The signing service needs warm-up time on Render free tier.** First request after inactivity may take 30–60 seconds. Use the `/ping` endpoint with an uptime monitor to keep it warm.

---

## Project Structure

```
VH-Wallet-Quai-Android-Integration/
├── assets/
│   └── logo.png
├── sdk/
│   └── src/main/kotlin/com/quai/sdk/
│       ├── QuaiWalletManager.kt
│       ├── QuaiTransactionSender.kt
│       ├── QuaiTransactionScanner.kt
│       └── QuaiJavaScriptBridge.kt
├── example/
│   └── QuaiExample.kt         ← Full usage examples
└── README.md
```

---

## License

MIT — free to use, modify, and distribute. Attribution appreciated.

---

<p align="center">
  Built with 🐝 by <strong>VertexHiveTech</strong> for <strong>VH Wallet</strong><br/>
  <sub>Pioneering multi-chain mobile wallets on Android</sub>
</p>
