package com.ianhanniballake.localstorage

import android.app.Activity
import android.app.Application
import android.app.Dialog
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import android.os.Bundle
import androidx.annotation.LayoutRes
import androidx.fragment.app.DialogFragment
import androidx.appcompat.app.AlertDialog
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.content.withStyledAttributes
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.SkuDetails
import com.android.billingclient.api.SkuDetailsParams

class DonateViewModel(
        application: Application
) : AndroidViewModel(application), BillingClientStateListener, PurchasesUpdatedListener {

    private val billingClient: BillingClient = BillingClient.newBuilder(application)
            .setListener(this)
            .build()

    init {
        billingClient.startConnection(this)
    }

    private val mutableSkuDetailsList = MutableLiveData<List<SkuDetails>>()

    val skuDetailsList: LiveData<List<SkuDetails>> = mutableSkuDetailsList

    var purchaseCompletedCallback: (responseCode: Int) -> Unit = { }

    override fun onBillingSetupFinished(responseCode: Int) {
        val allSkus = mutableListOf<String>()
        if (BuildConfig.DEBUG) {
            allSkus.add("android.test.purchased")
            allSkus.add("android.test.canceled")
            allSkus.add("android.test.refunded")
            allSkus.add("android.test.item_unavailable")
        }
        allSkus.addAll(getApplication<Application>().resources
                .getStringArray(R.array.donate_in_app_sku_array))
        billingClient.querySkuDetailsAsync(SkuDetailsParams.newBuilder()
                .setSkusList(allSkus)
                .setType(BillingClient.SkuType.INAPP)
                .build()) { _, skuDetailsList ->
            mutableSkuDetailsList.value = skuDetailsList.sortedBy { it.priceAmountMicros }
        }
        val purchasesResult = billingClient.queryPurchases(BillingClient.SkuType.INAPP)
        purchasesResult.purchasesList?.forEach { purchase ->
            billingClient.consumeAsync(purchase.purchaseToken) { _, _ ->
            }
        }
    }

    fun purchase(activity: Activity, sku: String?): Int = billingClient
            .launchBillingFlow(activity, BillingFlowParams.newBuilder()
                    .setSku(sku)
                    .setType(BillingClient.SkuType.INAPP)
                    .build())

    override fun onPurchasesUpdated(responseCode: Int, purchases: MutableList<Purchase>?) {
        purchases?.forEach { purchase ->
            billingClient.consumeAsync(purchase.purchaseToken) { responseCode, _ ->
                purchaseCompletedCallback(responseCode)
            }
        }
    }

    override fun onBillingServiceDisconnected() {
        billingClient.startConnection(this)
    }

    override fun onCleared() {
        billingClient.endConnection()
    }
}

class DonateDialogFragment : DialogFragment() {
    private val viewModel: DonateViewModel by lazy {
        ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory
                .getInstance(requireActivity().application))[DonateViewModel::class.java]
    }
    private lateinit var adapter: ArrayAdapter<CharSequence>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requireContext().withStyledAttributes(attrs = R.styleable.AlertDialog, defStyleAttr = R.attr.alertDialogStyle) {
            @LayoutRes val listItemLayout = getResourceId(R.styleable.AlertDialog_listItemLayout, 0)
            adapter = ArrayAdapter(requireContext(), listItemLayout)
        }
        viewModel.purchaseCompletedCallback = { responseCode ->
            if (responseCode == BillingClient.BillingResponse.OK) {
                Toast.makeText(requireContext(), R.string.donate_thank_you, Toast.LENGTH_SHORT).show()
            }
            dismiss()
        }
        viewModel.skuDetailsList.observe(this, Observer { skuDetailsList ->
            adapter.clear()
            if (skuDetailsList != null) {
                adapter.addAll(skuDetailsList.map {
                    it.description + " (" + it.price + ")"
                })
            }
            adapter.notifyDataSetChanged()
        })
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return AlertDialog.Builder(requireContext())
                .setTitle(R.string.donate_header)
                .setAdapter(adapter, null)
                .create().also { alertDialog ->
                    alertDialog.listView.setOnItemClickListener { _, _, position, _ ->
                        viewModel.skuDetailsList.value?.let { skuDetailsList ->
                            val sku = skuDetailsList[position].sku
                            val responseCode = viewModel.purchase(requireActivity(), sku)
                            if (responseCode != BillingClient.BillingResponse.OK) {
                                dismiss()
                            }
                        }
                    }
                }
    }
}