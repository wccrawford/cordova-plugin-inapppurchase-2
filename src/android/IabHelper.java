/* Copyright (c) 2012 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alexdisler.inapppurchases;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import android.content.pm.ResolveInfo;

import org.json.JSONException;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender.SendIntentException;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;

// import com.android.vending.billing.IInAppBillingService;
import com.android.billingclient.api.PurchasesResponseListener;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClient.BillingResponseCode;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.Purchase.PurchasesResult;
import com.android.billingclient.api.BillingClient.SkuType;
import com.android.billingclient.api.SkuDetails;
import com.android.billingclient.api.SkuDetailsParams;
import com.android.billingclient.api.SkuDetailsResponseListener;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.AcknowledgePurchaseResponseListener;
import com.android.billingclient.api.BillingClient.FeatureType;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.ConsumeParams;
import com.android.billingclient.api.ConsumeResponseListener;

import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

import static com.alexdisler.inapppurchases.InAppBillingV6.BILLING_API_VERSION;

/**
 * Provides convenience methods for in-app billing. You can create one instance of this
 * class for your application and use it to process in-app billing operations.
 * It provides synchronous (blocking) and asynchronous (non-blocking) methods for
 * many common in-app billing operations, as well as automatic signature
 * verification.
 *
 * After instantiating, you must perform setup in order to start using the object.
 * To perform setup, call the {@link #startSetup} method and provide a listener;
 * that listener will be notified when setup is complete, after which (and not before)
 * you may call other methods.
 *
 * After setup is complete, you will typically want to request an inventory of owned
 * items and subscriptions. See {@link #queryInventoryAsync}
 * and related methods.
 *
 * When you are done with this object, don't forget to call {@link #dispose}
 * to ensure proper cleanup. This object holds a binding to the in-app billing
 * service, which will leak unless you dispose of it correctly. If you created
 * the object on an Activity's onCreate method, then the recommended
 * place to dispose of it is the Activity's onDestroy method.
 *
 * A note about threading: When using this object from a background thread, you may
 * call the blocking versions of methods; when using from a UI thread, call
 * only the asynchronous versions and handle the results via callbacks.
 * Also, notice that you can only call one asynchronous operation at a time;
 * attempting to start a second asynchronous operation while the first one
 * has not yet completed will result in an exception being thrown.
 *
 * @author Bruno Oliveira (Google)
 *
 */
public class IabHelper implements PurchasesUpdatedListener {
    protected static final String TAG = "google.payments Helper";

  	public static final int QUERY_SKU_DETAILS_BATCH_SIZE = 20;

    // Is debug logging enabled?
    boolean mDebugLog = false;
    String mDebugTag = "IabHelper";

    // Can we skip the online purchase verification?
    // (Only allowed if the app is debuggable)
	  private boolean mSkipPurchaseVerification = false;

    // Is setup done?
    boolean mSetupDone = false;

    // Has this object been disposed of? (If so, we should ignore callbacks, etc)
    boolean mDisposed = false;

    // Are subscriptions supported?
    boolean mSubscriptionsSupported = false;

    // Is an asynchronous operation in progress?
    // (only one at a time can be in progress)
    boolean mAsyncInProgress = false;

    // (for logging/debugging)
    // if mAsyncInProgress == true, what asynchronous operation is in progress?
    String mAsyncOperation = "";

    // Context we were passed during initialization
    Context mContext;

    // Connection to the service
//     ServiceConnection mServiceConn;

    BillingClient billingClient;

    // The request code used to launch purchase flow
    int mRequestCode;

    // The item type of the current purchase flow
    String mPurchasingItemType;

    // Public key for verifying signature, in base64 encoding
    String mSignatureBase64 = null;

    // Billing response codes
    public static final int BILLING_RESPONSE_RESULT_OK = 0;
    public static final int BILLING_RESPONSE_RESULT_USER_CANCELED = 1;
    public static final int BILLING_RESPONSE_RESULT_BILLING_UNAVAILABLE = 3;
    public static final int BILLING_RESPONSE_RESULT_ITEM_UNAVAILABLE = 4;
    public static final int BILLING_RESPONSE_RESULT_DEVELOPER_ERROR = 5;
    public static final int BILLING_RESPONSE_RESULT_ERROR = 6;
    public static final int BILLING_RESPONSE_RESULT_ITEM_ALREADY_OWNED = 7;
    public static final int BILLING_RESPONSE_RESULT_ITEM_NOT_OWNED = 8;

    // IAB Helper error codes
    public static final int IABHELPER_ERROR_BASE = -1000;
    public static final int IABHELPER_REMOTE_EXCEPTION = -1001;
    public static final int IABHELPER_BAD_RESPONSE = -1002;
    public static final int IABHELPER_VERIFICATION_FAILED = -1003;
    public static final int IABHELPER_SEND_INTENT_FAILED = -1004;
    public static final int IABHELPER_USER_CANCELLED = -1005;
    public static final int IABHELPER_UNKNOWN_PURCHASE_RESPONSE = -1006;
    public static final int IABHELPER_MISSING_TOKEN = -1007;
    public static final int IABHELPER_UNKNOWN_ERROR = -1008;
    public static final int IABHELPER_SUBSCRIPTIONS_NOT_AVAILABLE = -1009;
    public static final int IABHELPER_INVALID_CONSUMPTION = -1010;

    // Keys for the responses from InAppBillingService
    public static final String RESPONSE_CODE = "RESPONSE_CODE";
    public static final String RESPONSE_GET_SKU_DETAILS_LIST = "DETAILS_LIST";
    public static final String RESPONSE_BUY_INTENT = "BUY_INTENT";
    public static final String RESPONSE_INAPP_PURCHASE_DATA = "INAPP_PURCHASE_DATA";
    public static final String RESPONSE_INAPP_SIGNATURE = "INAPP_DATA_SIGNATURE";
    public static final String RESPONSE_INAPP_ITEM_LIST = "INAPP_PURCHASE_ITEM_LIST";
    public static final String RESPONSE_INAPP_PURCHASE_DATA_LIST = "INAPP_PURCHASE_DATA_LIST";
    public static final String RESPONSE_INAPP_SIGNATURE_LIST = "INAPP_DATA_SIGNATURE_LIST";
    public static final String INAPP_CONTINUATION_TOKEN = "INAPP_CONTINUATION_TOKEN";

    // Item types
    public static final String ITEM_TYPE_INAPP = "inapp";
    public static final String ITEM_TYPE_SUBS = "subs";

    // some fields on the getIabSkuDetails response bundle
    public static final String GET_SKU_DETAILS_ITEM_LIST = "ITEM_ID_LIST";
    public static final String GET_SKU_DETAILS_ITEM_TYPE_LIST = "ITEM_TYPE_LIST";

    /**
     * Creates an instance. After creation, it will not yet be ready to use. You must perform
     * setup by calling {@link #startSetup} and wait for setup to complete. This constructor does not
     * block and is safe to call from a UI thread.
     *
     * @param ctx Your application or Activity context. Needed to bind to the in-app billing service.
     * @param base64PublicKey Your application's public key, encoded in base64.
     *     This is used for verification of purchase signatures. You can find your app's base64-encoded
     *     public key in your application's page on Google Play Developer Console. Note that this
     *     is NOT your "developer public key".
     */
    public IabHelper(Context ctx, String base64PublicKey) {
        mContext = ctx.getApplicationContext();
        mSignatureBase64 = base64PublicKey;
        Log.d(TAG, "IAB helper created.");
    }

    @Override
    public void onPurchasesUpdated(
        BillingResult billingResult, List<Purchase> purchases) {
        // Logic here
        IabResult result;
        Log.d(TAG, "IabHelper onPurchasesUpdated");
        Inventory inventory = new Inventory();

        checkNotDisposed();
        checkSetupDone("handleActivityResult");

        // end of async purchase operation that started on launchPurchaseFlow
        flagEndAsync();

        int responseCode = billingResult.getResponseCode();

        if(responseCode != BillingResponseCode.OK) {
            logError("Error response for purchases");
            result = new IabResult(IABHELPER_BAD_RESPONSE, "Error response for purchases");
            if (mPurchaseListener != null) mPurchaseListener.onIabPurchaseFinished(result, null);
            return;
        }

        if (purchases == null) {
            logError("Null data in IAB activity result.");
            result = new IabResult(IABHELPER_BAD_RESPONSE, "Null data in IAB result");
            if (mPurchaseListener != null) mPurchaseListener.onIabPurchaseFinished(result, null);
             return;
        }

        for(Purchase purchase : purchases) {
            // Only allow purchase verification to be skipped if we are debuggable
            boolean skipPurchaseVerification = (this.mSkipPurchaseVerification  &&
                     ((mContext.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0));

            List<String> skus = purchase.getSkus();
            queryInventoryAsync(true, skus, new IabHelper.QueryInventoryFinishedListener() {
                public void onQueryInventoryFinished(IabResult result, Inventory inventory) {
                    for (String sku : skus) {
                        IabSkuDetails skuDetails = inventory.getIabSkuDetails(sku);

                        try {
                            IabPurchase iabPurchase = new IabPurchase(skuDetails.getType(), purchase.getOriginalJson(), purchase.getSignature());
                            // Verify signature
                            if (!skipPurchaseVerification) {
                                if (!Security.verifyPurchase(mSignatureBase64, purchase.getOriginalJson(), purchase.getSignature())) {
                                    logError("Purchase signature verification FAILED for sku " + sku);

                                    result = new IabResult(IABHELPER_VERIFICATION_FAILED, "Signature verification failed for sku " + sku);

                                    if (mPurchaseListener != null)
                                        mPurchaseListener.onIabPurchaseFinished(result, iabPurchase);
                                    return;
                                }
                                Log.d(TAG, "Purchase signature successfully verified.");
                            }

                            if (mPurchaseListener != null) {
                                mPurchaseListener.onIabPurchaseFinished(new IabResult(BILLING_RESPONSE_RESULT_OK, "Success"), iabPurchase);
                            }

                        } catch (JSONException e) {
                            result = new IabResult(IABHELPER_VERIFICATION_FAILED, "Failed to parse purchase data.");
                            if (mPurchaseListener != null)
                                mPurchaseListener.onIabPurchaseFinished(result, null);
                            return;
                        }

                    }
                }
            });
        }
    }

    /**
     * Enables or disable debug logging through LogCat.
     */
    public void enableDebugLogging(boolean enable, String tag) {
        checkNotDisposed();
        mDebugLog = enable;
        mDebugTag = tag;
    }

    public void enableDebugLogging(boolean enable) {
        checkNotDisposed();
        mDebugLog = enable;
    }

    public void setSkipPurchaseVerification(boolean shouldSkipPurchaseVerification) {
        mSkipPurchaseVerification = shouldSkipPurchaseVerification;
    }

    /**
     * Callback for setup process. This listener's {@link #onIabSetupFinished} method is called
     * when the setup process is complete.
     */
    public interface OnIabSetupFinishedListener {
        /**
         * Called to notify that setup is complete.
         *
         * @param result The result of the setup process.
         */
        public void onIabSetupFinished(IabResult result);
    }

    /**
     * Starts the setup process. This will start up the setup process asynchronously.
     * You will be notified through the listener when the setup process is complete.
     * This method is safe to call from a UI thread.
     *
     * @param listener The listener to notify when the setup process is complete.
     */
    public void startSetup(final OnIabSetupFinishedListener listener) {
        // If already set up, can't do it again.
        checkNotDisposed();
        if (mSetupDone) throw new IllegalStateException("IAB helper is already set up.");

        billingClient = BillingClient
            .newBuilder(mContext)
            .setListener(this)
            .enablePendingPurchases()
            .build();
        billingClient.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(BillingResult billingResult) {
                // Logic from ServiceConnection.onServiceConnected should be moved here.
                if (mDisposed) return;
                Log.d(TAG, "Billing service connected.");
//                 mService = IInAppBillingService.Stub.asInterface(service);
                String packageName = mContext.getPackageName();
//                 try {
                    Log.d(TAG, "Checking for in-app billing " + BILLING_API_VERSION + " support.");

                    // check for in-app billing support
                    int response = billingResult.getResponseCode();
                    if (response != BILLING_RESPONSE_RESULT_OK) {
                        if (listener != null) listener.onIabSetupFinished(new IabResult(response,
                                "Error checking for billing v" + BILLING_API_VERSION + " support."));

                        // if in-app purchases aren't supported, neither are subscriptions.
                        mSubscriptionsSupported = false;
                        return;
                    }
                    Log.d(TAG, "In-app billing version " + BILLING_API_VERSION + " supported for " + packageName);

                    // check for subscriptions support
//                     response = mService.isBillingSupported(BILLING_API_VERSION, packageName, ITEM_TYPE_SUBS);
                    response = billingClient.isFeatureSupported(BillingClient.FeatureType.SUBSCRIPTIONS).getResponseCode();
                    if (response == BILLING_RESPONSE_RESULT_OK) {
                        Log.d(TAG, "Subscriptions AVAILABLE.");
                        mSubscriptionsSupported = true;
                    }
                    else {
                        Log.d(TAG, "Subscriptions NOT AVAILABLE. Response: " + response);
                    }

                    mSetupDone = true;
//                 }
//                 catch (RemoteException e) {
// //                     if (listener != null) {
// //                         listener.onIabSetupFinished(new IabResult(IABHELPER_REMOTE_EXCEPTION,
// //                                                     "RemoteException while setting up in-app billing."));
// //                     }
//                     e.printStackTrace();
//                     return;
//                 }

                if (listener != null) {
                    listener.onIabSetupFinished(new IabResult(BILLING_RESPONSE_RESULT_OK, "Setup successful."));
                }
            }

            @Override
            public void onBillingServiceDisconnected() {
                // Logic from ServiceConnection.onServiceDisconnected should be moved here.
                Log.d(TAG, "Billing service disconnected.");
//                 mService = null;
            }
        });

    }

    /**
     * Dispose of object, releasing resources. It's very important to call this
     * method when you are done with this object. It will release any resources
     * used by it such as service connections. Naturally, once the object is
     * disposed of, it can't be used again.
     */
    public void dispose() {
        Log.d(TAG, "Disposing.");
        mSetupDone = false;
//         if (mServiceConn != null) {
//             Log.d(TAG, "Unbinding from service.");
//             if (mContext != null && mService != null) mContext.unbindService(mServiceConn);
//         }
        mDisposed = true;
        mContext = null;
//         mServiceConn = null;
//         mService = null;
        mPurchaseListener = null;
    }

    private void checkNotDisposed() {
        if (mDisposed) throw new IllegalStateException("IabHelper was disposed of, so it cannot be used.");
    }

    /** Returns whether subscriptions are supported. */
    public boolean subscriptionsSupported() {
        checkNotDisposed();
        return mSubscriptionsSupported;
    }


    /**
     * Callback that notifies when a purchase is finished.
     */
    public interface OnIabPurchaseFinishedListener {
        /**
         * Called to notify that an in-app purchase finished. If the purchase was successful,
         * then the sku parameter specifies which item was purchased. If the purchase failed,
         * the sku and extraData parameters may or may not be null, depending on how far the purchase
         * process went.
         *
         * @param result The result of the purchase.
         * @param purchase The purchase information (null if purchase failed)
         */
        public void onIabPurchaseFinished(IabResult result, IabPurchase purchase);
    }

    // The listener registered on launchPurchaseFlow, which we have to call back when
    // the purchase finishes
    OnIabPurchaseFinishedListener mPurchaseListener;

    /**
     * Initiate the UI flow for an in-app purchase. Call this method to initiate an in-app purchase,
     * which will involve bringing up the Google Play screen. The calling activity will be paused while
     * the user interacts with Google Play, and the result will be delivered via
     * onPurchasesUpdated. This method
     * MUST be called from the UI thread of the Activity.
     *
     * @param act The calling activity.
     * @param sku The sku of the item to purchase.
     * @param itemType indicates if it's a product or a subscription (ITEM_TYPE_INAPP or ITEM_TYPE_SUBS)
     * @param extraData Extra data (developer payload), which will be returned with the purchase data
     *     when the purchase completes. This extra data will be permanently bound to that purchase
     *     and will always be returned when the purchase is queried.
     */
    public void launchPurchaseFlow(Activity act, String sku, String itemType,
                                   OnIabPurchaseFinishedListener listener, String extraData) {
        checkNotDisposed();
        checkSetupDone("launchPurchaseFlow");
        flagStartAsync("launchPurchaseFlow");
        IabResult result;

      Log.d(TAG, "launchPurchaseFlow:" + sku + " " + itemType);

      if (itemType.equals(ITEM_TYPE_SUBS) && !mSubscriptionsSupported) {
            IabResult r = new IabResult(IABHELPER_SUBSCRIPTIONS_NOT_AVAILABLE,
                    "Subscriptions are not available.");
            flagEndAsync();
        }

        mPurchaseListener = listener;
        mPurchasingItemType = itemType;
        List<String> skuList = new ArrayList<> ();
        skuList.add(sku);
        SkuDetailsParams.Builder params = SkuDetailsParams.newBuilder();
        params.setSkusList(skuList).setType(itemType);

        // Store the sku so we can match it when the data comes back.
        billingClient.querySkuDetailsAsync(params.build(),
          new SkuDetailsResponseListener() {
            @Override
            public void onSkuDetailsResponse(BillingResult billingResult,
                                             List<SkuDetails> skuDetailsList) {
              Log.d(TAG, "purch skus: " + skuDetailsList);
              // Process the result.
              for(SkuDetails skuDetails : skuDetailsList) {
                Log.d(TAG, "sku deets: " + skuDetails);
                Log.d(TAG, "sku id: " + skuDetails.getSku());

                if(sku.equals(skuDetails.getSku())) {
                  Log.d(TAG, "got sku: " + skuDetails);
                  BillingFlowParams purchaseParams =
                    BillingFlowParams.newBuilder()
                      .setSkuDetails(skuDetails)
                      .build();

                  billingClient.launchBillingFlow(act, purchaseParams);
                }
              }
            }
          });
    }

    /**
     * Handles an activity result that's part of the purchase flow in in-app billing. If you
     * are calling {@link #launchPurchaseFlow}, then you must call this method from your
     * Activity's {@link android.app.Activity@onActivityResult} method. This method
     * MUST be called from the UI thread of the Activity.
     *
     * @param requestCode The requestCode as you received it.
     * @param resultCode The resultCode as you received it.
     * @param data The data (Intent) as you received it.
     * @return Returns true if the result was related to a purchase flow and was handled;
     *     false if the result was not related to a purchase, in which case you should
     *     handle it normally.
     */
    public boolean handleActivityResult(int requestCode, int resultCode, Intent data) {
        return true;
    }

    /**
     * Listener that notifies when an inventory query operation completes.
     */
    public interface QueryInventoryFinishedListener {
        /**
         * Called to notify that an inventory query operation completed.
         *
         * @param result The result of the operation.
         * @param inv The inventory.
         */
        public void onQueryInventoryFinished(IabResult result, Inventory inv);
    }


    /**
     * Asynchronous wrapper for inventory query. This will perform an inventory
     * query, but will do so asynchronously
     * and call back the specified listener upon completion. This method is safe to
     * call from a UI thread.
     *
     * @param queryIabSkuDetails
     * @param moreSkus
     * @param listener The listener to notify when the refresh operation completes.
     */
    public void queryInventoryAsync(final boolean queryIabSkuDetails,
                               final List<String> moreSkus,
                               final QueryInventoryFinishedListener listener) {
        Log.d(TAG, "IabHelper queryInventoryAsync" + queryIabSkuDetails + " " + moreSkus);
        final Handler handler = new Handler();
        checkNotDisposed();
        checkSetupDone("queryInventory");
        flagStartAsync("refresh inventory");
        (new Thread(new Runnable() {
            public void run() {
                IabResult result = new IabResult(BILLING_RESPONSE_RESULT_OK, "Inventory refresh successful.");
                Inventory inv = new Inventory();
                try {
                    try {
                        queryAllPurchasesAsync(inv, moreSkus, new OnQueryAllPurchasesFinishedListener() {
                            @Override
                            public void onQueryAllPurchasesFinished(List<IabPurchase> iabPurchases) {
                                Log.d(TAG, "onQueryAllPurchasesFinished: " + iabPurchases);
                                try {
                                    if (queryIabSkuDetails) {
                                        OnQueryIabSkuDetailsFinishedListener detailsListener = new OnQueryIabSkuDetailsFinishedListener() {

                                            protected boolean subsProcessed = false;
                                            protected boolean itemsProcessed = false;

                                            public void onAllProcessed() {
                                                Log.d(TAG, "oqapf onAllProcessed: " + subsProcessed + " " + itemsProcessed);
                                                if (subsProcessed && itemsProcessed) {
                                                    flagEndAsync();

                                                    IabResult result = new IabResult(BILLING_RESPONSE_RESULT_OK, "Inventory refresh successful.");
                                                    final IabResult result_f = result;
                                                    final Inventory inv_f = inv;
                                                    if (!mDisposed && listener != null) {
                                                        handler.post(new Runnable() {
                                                            public void run() {
                                                                listener.onQueryInventoryFinished(result_f, inv_f);
                                                            }
                                                        });
                                                    }
                                                }
                                            }

                                            public void onQueryIabSkuDetailsFinished(List<IabSkuDetails> iabSkuDetailsList, String itemType) {
                                                Log.d(TAG, "listener: " + " " + itemType + iabSkuDetailsList);
                                                for (IabSkuDetails isd : iabSkuDetailsList) {
                                                    Log.d(TAG, "isd: " + isd);
                                                }

                                                if (itemType == ITEM_TYPE_SUBS) {
                                                    subsProcessed = true;
                                                } else if (itemType == ITEM_TYPE_INAPP) {
                                                    itemsProcessed = true;
                                                }

                                                onAllProcessed();
                                            }
                                        };

                                        try {
                                            queryIabSkuDetailsAsync(ITEM_TYPE_INAPP, inv, moreSkus, detailsListener);
                                            queryIabSkuDetailsAsync(ITEM_TYPE_SUBS, inv, moreSkus, detailsListener);
                                        } catch (RemoteException e) {
                                            throw new IabException(IABHELPER_REMOTE_EXCEPTION, "Remote exception while refreshing inventory.", e);
                                        }
                                    } else {
                                        flagEndAsync();

                                        IabResult result = new IabResult(BILLING_RESPONSE_RESULT_OK, "Inventory refresh successful.");
                                        final IabResult result_f = result;
                                        final Inventory inv_f = inv;
                                        if (!mDisposed && listener != null) {
                                            handler.post(new Runnable() {
                                                public void run() {
                                                    listener.onQueryInventoryFinished(result_f, inv_f);
                                                }
                                            });
                                        }
                                    }
                                } catch (IabException ex) {
                                    IabResult result = ex.getResult();

                                    flagEndAsync();

                                    final IabResult result_f = result;
                                    final Inventory inv_f = inv;
                                    if (!mDisposed && listener != null) {
                                        handler.post(new Runnable() {
                                            public void run() {
                                                listener.onQueryInventoryFinished(result_f, inv_f);
                                            }
                                        });
                                    }
                                }
                            }
                        });
                    } catch (RemoteException e) {
                        throw new IabException(IABHELPER_REMOTE_EXCEPTION, "Remote exception while refreshing inventory.", e);
                    } catch (NullPointerException e) {
                        throw new IabException(IABHELPER_UNKNOWN_ERROR, "NullPointerException while refreshing inventory.", e);
                    }
                }

                catch (IabException ex) {
                    result = ex.getResult();

                    flagEndAsync();

                    final IabResult result_f = result;
                    final Inventory inv_f = inv;
                    if (!mDisposed && listener != null) {
                        handler.post(new Runnable() {
                            public void run() {
                                listener.onQueryInventoryFinished(result_f, inv_f);
                            }
                        });
                    }
                }

            }
        })).start();
    }

    public void queryInventoryAsync(QueryInventoryFinishedListener listener) {
        queryInventoryAsync(true, null, listener);
    }

    public void queryInventoryAsync(boolean queryIabSkuDetails, QueryInventoryFinishedListener listener) {
        queryInventoryAsync(queryIabSkuDetails, null, listener);
    }

    /**
     * Callback that notifies when a consumption operation finishes.
     */
    public interface OnAcknowledgeFinishedListener {
        /**
         * Called to notify that a consumption has finished.
         *
         * @param purchase The purchase that was (or was to be) acknowledged.
         * @param result The result of the consumption operation.
         */
        public void onAcknowledgeFinished(IabPurchase purchase, IabResult result);
    }

    /**
     * Callback that notifies when a consumption operation finishes.
     */
    public interface OnConsumeFinishedListener {
        /**
         * Called to notify that a consumption has finished.
         *
         * @param purchase The purchase that was (or was to be) consumed.
         * @param result The result of the consumption operation.
         */
        public void onConsumeFinished(IabPurchase purchase, IabResult result);
    }

    /**
     * Callback that notifies when a multi-item consumption operation finishes.
     */
    public interface OnConsumeMultiFinishedListener {
        /**
         * Called to notify that a consumption of multiple items has finished.
         *
         * @param purchases The purchases that were (or were to be) consumed.
         * @param results The results of each consumption operation, corresponding to each
         *     sku.
         */
        public void onConsumeMultiFinished(List<IabPurchase> purchases, List<IabResult> results);
    }

    /**
     * Asynchronous wrapper to item consumption.
     * Performs the consumption in the background and notifies completion through
     * the provided listener. This method is safe to call from a UI thread.
     *
     * @param purchase The purchase to be consumed.
     * @param listener The listener to notify when the consumption operation finishes.
     */
    public void consumeAsync(IabPurchase purchase, OnConsumeFinishedListener listener) {
        checkNotDisposed();
        checkSetupDone("consume");
        List<IabPurchase> purchases = new ArrayList<IabPurchase>();
        purchases.add(purchase);
        consumeAsyncInternal(purchases, listener, null);
    }

    /**
     * Same as above consumeAsync, but for multiple items at once.
     * @param purchases The list of PurchaseInfo objects representing the purchases to consume.
     * @param listener The listener to notify when the consumption operation finishes.
     */
    public void consumeAsync(List<IabPurchase> purchases, OnConsumeMultiFinishedListener listener) {
        checkNotDisposed();
        checkSetupDone("consume");
        consumeAsyncInternal(purchases, null, listener);
    }

    /**
     * Returns a human-readable description for the given response code.
     *
     * @param code The response code
     * @return A human-readable string explaining the result code.
     *     It also includes the result code numerically.
     */
    public static String getResponseDesc(int code) {
        String[] iab_msgs = ("0:OK/1:User Canceled/2:Unknown/" +
                "3:Billing Unavailable/4:Item unavailable/" +
                "5:Developer Error/6:Error/7:Item Already Owned/" +
                "8:Item not owned").split("/");
        String[] iabhelper_msgs = ("0:OK/-1001:Remote exception during initialization/" +
                                   "-1002:Bad response received/" +
                                   "-1003:Purchase signature verification failed/" +
                                   "-1004:Send intent failed/" +
                                   "-1005:User cancelled/" +
                                   "-1006:Unknown purchase response/" +
                                   "-1007:Missing token/" +
                                   "-1008:Unknown error/" +
                                   "-1009:Subscriptions not available/" +
                                   "-1010:Invalid consumption attempt").split("/");

        if (code <= IABHELPER_ERROR_BASE) {
            int index = IABHELPER_ERROR_BASE - code;
            if (index >= 0 && index < iabhelper_msgs.length) return iabhelper_msgs[index];
            else return String.valueOf(code) + ":Unknown IAB Helper Error";
        }
        else if (code < 0 || code >= iab_msgs.length)
            return String.valueOf(code) + ":Unknown";
        else
            return iab_msgs[code];
    }


    // Checks that setup was done; if not, throws an exception.
    void checkSetupDone(String operation) {
        if (!mSetupDone) {
            logError("Illegal state for operation (" + operation + "): IAB helper is not set up.");
            throw new IllegalStateException("IAB helper is not set up. Can't perform operation: " + operation);
        }
    }

    // Workaround to bug where sometimes response codes come as Long instead of Integer
    int getResponseCodeFromBundle(Bundle b) {
        Object o = b.get(RESPONSE_CODE);
        if (o == null) {
            Log.d(TAG, "Bundle with null response code, assuming OK (known issue)");
            return BILLING_RESPONSE_RESULT_OK;
        }
        else if (o instanceof Integer) return ((Integer)o).intValue();
        else if (o instanceof Long) return (int)((Long)o).longValue();
        else {
            logError("Unexpected type for bundle response code.");
            logError(o.getClass().getName());
            throw new RuntimeException("Unexpected type for bundle response code: " + o.getClass().getName());
        }
    }

    // Workaround to bug where sometimes response codes come as Long instead of Integer
    int getResponseCodeFromIntent(Intent i) {
        Object o = i.getExtras().get(RESPONSE_CODE);
        if (o == null) {
            logError("Intent with no response code, assuming OK (known issue)");
            return BILLING_RESPONSE_RESULT_OK;
        }
        else if (o instanceof Integer) return ((Integer)o).intValue();
        else if (o instanceof Long) return (int)((Long)o).longValue();
        else {
            logError("Unexpected type for intent response code.");
            logError(o.getClass().getName());
            throw new RuntimeException("Unexpected type for intent response code: " + o.getClass().getName());
        }
    }

    void flagStartAsync(String operation) {
        if (mAsyncInProgress) throw new IllegalStateException("Can't start async operation (" +
                operation + ") because another async operation(" + mAsyncOperation + ") is in progress.");
        mAsyncOperation = operation;
        mAsyncInProgress = true;
        Log.d(TAG, "Starting async operation: " + operation);
    }

    void flagEndAsync() {
        Log.d(TAG, "Ending async operation: " + mAsyncOperation);
        mAsyncOperation = "";
        mAsyncInProgress = false;
    }

    public interface OnQueryAllPurchasesFinishedListener {
        public void onQueryAllPurchasesFinished(List<IabPurchase> iabPurchases);
    }

    int queryAllPurchasesAsync(Inventory inv, List<String> moreSkus, OnQueryAllPurchasesFinishedListener listener) throws RemoteException {
        OnQueryPurchasesFinishedListener purchasesListener = new OnQueryPurchasesFinishedListener() {
            boolean inappFinished = false;
            boolean subsFinished = false;
            ArrayList<IabPurchase> iabPurchasesList = new ArrayList<IabPurchase>();

            public void onAllFinished() {
                Log.d(TAG, "queryAllPurchasesAsync onAllFinished" + inappFinished + " " + subsFinished);
                if(inappFinished && subsFinished) {
                    listener.onQueryAllPurchasesFinished(iabPurchasesList);
                }
            }

            @Override
            public void onQueryPurchasesFinished(List<IabPurchase> iabPurchases, String itemType) {
                Log.d(TAG, "onQueryPurchasesFinished: " + iabPurchases + " " + itemType);
                if(itemType.equals(ITEM_TYPE_INAPP)) {
                    inappFinished = true;
                } else if (itemType.equals(ITEM_TYPE_SUBS)) {
                    subsFinished = true;
                }

                iabPurchasesList.addAll(iabPurchases);

                onAllFinished();
            }
        };

        queryPurchasesAsync(ITEM_TYPE_INAPP, inv, moreSkus, purchasesListener);
        queryPurchasesAsync(ITEM_TYPE_SUBS, inv, moreSkus, purchasesListener);

        return BILLING_RESPONSE_RESULT_OK;
    }

    public interface OnQueryPurchasesFinishedListener {
        public void onQueryPurchasesFinished(List<IabPurchase> iabPurchases, String itemType);
    }

    int queryPurchasesAsync(String itemType, Inventory inv, List<String> moreSkus, OnQueryPurchasesFinishedListener listener) throws RemoteException {
        Log.d(TAG, "queryPurchasesAsync() Querying SKU details.");

        billingClient.queryPurchasesAsync(itemType, new PurchasesResponseListener() {
            @Override
            public void onQueryPurchasesResponse(BillingResult billingResult, List<Purchase> purchases) {
                ArrayList<IabPurchase> iabPurchasesList = new ArrayList<IabPurchase>();

                Log.d(TAG, "onQueryPurchasesResponse: " + itemType + " " + purchases);
                for(Purchase purchase: purchases) {
                    try {
                        IabPurchase iabPurchase = new IabPurchase(itemType, purchase.getOriginalJson(), purchase.getSignature());
                        Log.d(TAG, "isd origin: " + iabPurchase);
                        inv.addPurchase(iabPurchase);
                        iabPurchasesList.add(iabPurchase);
                    } catch (JSONException e) {
                        Log.e(TAG, "onQueryPurchasesResponse: JSON exception");
                    }
                }
                Log.d(TAG, "onPurchasesResponse: " + iabPurchasesList);

                listener.onQueryPurchasesFinished(iabPurchasesList, itemType);
            }
        });

        return BILLING_RESPONSE_RESULT_OK;
    }

    public interface OnQueryIabSkuDetailsFinishedListener {
        public void onQueryIabSkuDetailsFinished(List<IabSkuDetails> iabSkuDetailsList, String itemType);
    }

    int queryIabSkuDetailsAsync(String itemType, Inventory inv, List<String> moreSkus, OnQueryIabSkuDetailsFinishedListener listener) throws RemoteException {
        Log.d(TAG, "queryIabSkuDetailsAsync() Querying SKU details.");
        SkuDetailsParams.Builder sdpBuilder = SkuDetailsParams.newBuilder();

        if(itemType != null && !itemType.equals("")) {
            sdpBuilder.setType(itemType);
        }

        if(moreSkus != null) {
            sdpBuilder.setSkusList(moreSkus);
        } else {
            sdpBuilder.setSkusList(inv.getAllOwnedSkus());
        }

        SkuDetailsParams skuDetailsParams = sdpBuilder
                .build();

        billingClient.querySkuDetailsAsync(skuDetailsParams, new SkuDetailsResponseListener() {
            @Override
            public void onSkuDetailsResponse(BillingResult billingResult, List<SkuDetails> list) {
                ArrayList<IabSkuDetails> iabSkuDetailsList = new ArrayList<IabSkuDetails>();

                for(SkuDetails skuDetails: list) {
                  Log.d(TAG, "onSkuDetailsResponse: " + list);
                  IabSkuDetails iabSkuDetails = new IabSkuDetails(itemType, skuDetails);
                  Log.d(TAG, "isd origin: " + iabSkuDetails);
                  inv.addIabSkuDetails(iabSkuDetails);
                  iabSkuDetailsList.add(iabSkuDetails);
                }
              Log.d(TAG, "onSkuDetailsResponse: " + iabSkuDetailsList);

              listener.onQueryIabSkuDetailsFinished(iabSkuDetailsList, itemType);
            }
        });

        return BILLING_RESPONSE_RESULT_OK;
    }

    int queryIabSkuDetails(String itemType, Inventory inv, List<String> moreSkus) throws RemoteException, JSONException {
        Log.d(TAG, "queryIabSkuDetails() Querying SKU details.");
        Set<String> storeSkus = new TreeSet<String>();
        final List<String> allOwnedSkus = inv.getAllOwnedSkus(itemType);
        storeSkus.addAll(inv.getAllOwnedSkus(itemType));
        if (moreSkus != null) {
            for (String sku : moreSkus) {
                if (!storeSkus.contains(sku)) {
                        storeSkus.add(sku);
                }
            }
        }
        if (storeSkus.size() == 0) {
            Log.d(TAG, "queryIabSkuDetails(): nothing to do because there are no SKUs.");
            return BILLING_RESPONSE_RESULT_OK;
        }

        // Split the sku list in blocks of no more than QUERY_SKU_DETAILS_BATCH_SIZE elements.
        ArrayList<ArrayList<String>> batches = new ArrayList<ArrayList<String>>();
        ArrayList<String> tmpBatch = new ArrayList<String>(QUERY_SKU_DETAILS_BATCH_SIZE);
        int iSku = 0;
        for (String sku : storeSkus) {
            tmpBatch.add(sku);
            iSku++;
            if (tmpBatch.size() == QUERY_SKU_DETAILS_BATCH_SIZE || iSku == storeSkus.size()) {
                batches.add(tmpBatch);
                tmpBatch = new ArrayList<String>(QUERY_SKU_DETAILS_BATCH_SIZE);
            }
        }

        Log.d(TAG, "queryIabSkuDetails() batches: " + batches.size() + ", " + batches);

        for (ArrayList<String> batch : batches) {
        SkuDetailsParams.Builder params = SkuDetailsParams.newBuilder();
        String skuType = itemType == ITEM_TYPE_INAPP ? SkuType.INAPP : SkuType.SUBS;
        params.setSkusList(batch).setType(skuType);
        billingClient.querySkuDetailsAsync(params.build(),
            new SkuDetailsResponseListener() {
                @Override
                public void onSkuDetailsResponse(BillingResult billingResult,
                                                 List<SkuDetails> skuDetailsList) {
                    // Process result
                    Log.d(TAG, "onIabSkuDetailsResponse");
                    for(SkuDetails skuDetails : skuDetailsList) {
                         Log.d(TAG, "Got sku details: " + skuDetails);
//                        Log.d(TAG, "Got sku details: " + skuDetails);
                        IabSkuDetails iabSkuDetails = new IabSkuDetails(itemType, skuDetails);
                        inv.addIabSkuDetails(iabSkuDetails);
                    }
                }
            });
        }

        return BILLING_RESPONSE_RESULT_OK;
    }

    public abstract class ConsumeAsyncFinishedListener implements ConsumeResponseListener {
        public boolean allConsumesStarted = false;
        public int totalConsumes = 0;
        public int consumesCompleted = 0;

        public abstract void onConsumeFinished();
    }

    void acknowledgeAsync(final IabPurchase purchase,
                              final OnAcknowledgeFinishedListener singleListener) {
        final Handler handler = new Handler();
        Log.d(TAG, "acknowledgeAsync: " + purchase);
        flagStartAsync("acknowledge");
        (new Thread(new Runnable() {
            public void run() {

                AcknowledgePurchaseResponseListener listener = new AcknowledgePurchaseResponseListener() {
                    @Override
                    public void onAcknowledgePurchaseResponse(BillingResult billingResult) {
                        IabResult result;

                        flagEndAsync();

                        if (billingResult.getResponseCode() == BillingResponseCode.OK) {
                            // Handle the success of the acknowledge operation.
                            Log.d(TAG, "Successfully acknowledged purchase");
                            result = new IabResult(BILLING_RESPONSE_RESULT_OK, "Successful acknowledge of purchase");
                        } else {
                            Log.d(TAG, "Error acknowledging purchase. " + billingResult.getDebugMessage());
                            result = new IabResult(billingResult.getResponseCode(), "Error acknowledging purchase.");
                        }

                        singleListener.onAcknowledgeFinished(purchase, result);
                    }
                };

                String token = purchase.getToken();
                String sku = purchase.getSku();
                if (token == null || token.equals("")) {
                    logError("Can't consume " + sku + ". No token.");
                    IabResult result =  new IabResult(BILLING_RESPONSE_RESULT_ERROR, "PurchaseInfo is missing token for sku: " + sku);
                    singleListener.onAcknowledgeFinished(purchase, result);
                } else {
                    Log.d(TAG, "Acknowledging sku: " + sku + ", token: " + token);

                    AcknowledgePurchaseParams acknowledgeParams =
                            AcknowledgePurchaseParams.newBuilder()
                                    .setPurchaseToken(purchase.getToken())
                                    .build();

                    billingClient.acknowledgePurchase(acknowledgeParams, listener);

                }

            }
        })).start();
    }

    void consumeAsyncInternal(final List<IabPurchase> purchases,
                              final OnConsumeFinishedListener singleListener,
                              final OnConsumeMultiFinishedListener multiListener) {
        final Handler handler = new Handler();
         flagStartAsync("consume");
         (new Thread(new Runnable() {
             public void run() {
                 final List<IabResult> results = new ArrayList<IabResult>();

                 ConsumeAsyncFinishedListener listener = new ConsumeAsyncFinishedListener() {
                     // Handle all consumes finishing
                     @Override
                     public void onConsumeFinished() {
                         if(allConsumesStarted && (consumesCompleted == totalConsumes)) {
                             flagEndAsync();

                             if (!mDisposed && singleListener != null) {
                                 handler.post(new Runnable() {
                                     public void run() {
                                         singleListener.onConsumeFinished(purchases.get(0), results.get(0));
                                     }
                                 });
                             }

                             if (!mDisposed && multiListener != null) {
                                 handler.post(new Runnable() {
                                     public void run() {
                                         multiListener.onConsumeMultiFinished(purchases, results);
                                     }
                                 });
                             }
                         }
                     }

                     @Override
                     public void onConsumeResponse(BillingResult billingResult, String purchaseToken) {
                         if (billingResult.getResponseCode() == BillingResponseCode.OK) {
                             // Handle the success of the consume operation.
                             Log.d(TAG, "Successfully consumed purchase: " + purchaseToken);
                             results.add(new IabResult(BILLING_RESPONSE_RESULT_OK, "Successful consume of purchase " + purchaseToken));
                         } else {
                             Log.d(TAG, "Error consuming consuming purchase " + purchaseToken + ". " + billingResult.getDebugMessage());
//                                     throw new IabException(billingResult.getResponseCode(), "Error consuming sku " + sku);
                             results.add(new IabResult(billingResult.getResponseCode(), "Error consuming purchase " + purchaseToken));
                         }

                         consumesCompleted++;

                         onConsumeFinished();
                     }
                 };

                 for (IabPurchase purchase : purchases) {
                     String token = purchase.getToken();
                     String sku = purchase.getSku();
                     if (token == null || token.equals("")) {
                         logError("Can't consume "+ sku + ". No token.");
                         results.add(new IabResult(BILLING_RESPONSE_RESULT_ERROR, "PurchaseInfo is missing token for sku: " + sku));
                     } else {
                         listener.totalConsumes += 1;
                         Log.d(TAG, "Consuming sku: " + sku + ", token: " + token);

                         ConsumeParams consumeParams =
                                 ConsumeParams.newBuilder()
                                         .setPurchaseToken(purchase.getToken())
                                         .build();

                         billingClient.consumeAsync(consumeParams, listener);

                     }
                 }

                 listener.allConsumesStarted = true;

             }
         })).start();
    }

    void logError(String msg) {
        Log.e(mDebugTag, "In-app billing error: " + msg);
    }

    void logWarn(String msg) {
        Log.w(mDebugTag, "In-app billing warning: " + msg);
    }
}
