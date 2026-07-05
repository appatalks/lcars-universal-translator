package com.appatalks.lcars_translator

import android.app.Activity
import android.util.Log
import com.android.billingclient.api.*
import kotlinx.coroutines.*

/**
 * Manages Google Play Billing for the one-time "Pro Supporter" tip-jar purchase.
 * No features are gated — this is purely to support the developer.
 */
class BillingManager(
    private val activity: Activity,
    private val settings: AppSettings
) : PurchasesUpdatedListener {

    companion object {
        private const val TAG = "BillingManager"
        const val PRODUCT_ID = "pro_supporter"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val billingClient: BillingClient = BillingClient.newBuilder(activity)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .build()
        )
        .build()

    private var productDetails: ProductDetails? = null

    /** Formatted price string from Play (e.g. "$1.99"). Null until product is queried. */
    var formattedPrice: String? = null
        private set

    var onSupporterStatusChanged: ((Boolean) -> Unit)? = null

    // ── Connection ────────────────────────────────────────────────────────

    fun startConnection() {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.d(TAG, "Billing client connected")
                    queryProduct()
                    queryExistingPurchases()
                } else {
                    Log.w(TAG, "Billing setup failed: ${result.debugMessage}")
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "Billing service disconnected")
            }
        })
    }

    // ── Query product details ─────────────────────────────────────────────

    private fun queryProduct() {
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_ID)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        )
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        billingClient.queryProductDetailsAsync(params) { result, detailsList ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK && detailsList.isNotEmpty()) {
                productDetails = detailsList[0]
                formattedPrice = detailsList[0].oneTimePurchaseOfferDetails?.formattedPrice
                Log.d(TAG, "Product loaded: $formattedPrice")
            } else {
                Log.w(TAG, "Product query failed or empty: ${result.debugMessage}")
            }
        }
    }

    // ── Check existing purchases (restore supporter status) ───────────────

    private fun queryExistingPurchases() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()

        billingClient.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                val isSupporter = purchases.any {
                    it.products.contains(PRODUCT_ID) &&
                            it.purchaseState == Purchase.PurchaseState.PURCHASED
                }
                settings.isSupporter = isSupporter
                onSupporterStatusChanged?.invoke(isSupporter)

                // Acknowledge any unacknowledged purchases
                purchases.filter {
                    it.purchaseState == Purchase.PurchaseState.PURCHASED && !it.isAcknowledged
                }.forEach { acknowledgePurchase(it) }
            }
        }
    }

    // ── Launch purchase flow ──────────────────────────────────────────────

    fun launchSupportPurchase(): Boolean {
        val details = productDetails ?: run {
            Log.w(TAG, "Product details not loaded yet")
            return false
        }

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .build()
                )
            )
            .build()

        val result = billingClient.launchBillingFlow(activity, flowParams)
        return result.responseCode == BillingClient.BillingResponseCode.OK
    }

    // ── Purchase callback ─────────────────────────────────────────────────

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.forEach { purchase ->
                    if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                        settings.isSupporter = true
                        onSupporterStatusChanged?.invoke(true)
                        if (!purchase.isAcknowledged) {
                            acknowledgePurchase(purchase)
                        }
                    }
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                Log.d(TAG, "User cancelled purchase")
            }
            else -> {
                Log.w(TAG, "Purchase failed: ${result.responseCode} — ${result.debugMessage}")
            }
        }
    }

    private fun acknowledgePurchase(purchase: Purchase) {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()

        scope.launch {
            billingClient.acknowledgePurchase(params) { result ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.d(TAG, "Purchase acknowledged")
                } else {
                    Log.w(TAG, "Acknowledge failed: ${result.debugMessage}")
                }
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    fun release() {
        scope.cancel()
        billingClient.endConnection()
    }
}
