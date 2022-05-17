/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.qns;

import android.content.Context;
import android.os.AsyncResult;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.support.annotation.NonNull;
import android.telephony.AccessNetworkConstants.AccessNetworkType;
import android.telephony.CellInfo;
import android.telephony.CellSignalStrength;
import android.telephony.CellSignalStrengthCdma;
import android.telephony.CellSignalStrengthGsm;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellSignalStrengthNr;
import android.telephony.CellSignalStrengthWcdma;
import android.telephony.SignalStrength;
import android.telephony.SignalStrengthUpdateRequest;
import android.telephony.SignalThresholdInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.telephony.data.ApnSetting;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * This class manages cellular threshold information registered from AccessNetworkEvaluator. It
 * extends QualityMonitor class to implement and notify the signal changes in Cellular RAT.
 */
public class CellularQualityMonitor extends QualityMonitor {

    private static final int EVENT_BACKHAUL_TIMER_EXPIRED = 1;
    private static final int MAX_THRESHOLD_COUNT =
            SignalThresholdInfo.MAXIMUM_NUMBER_OF_THRESHOLDS_ALLOWED;
    private final String mTag;
    private final Handler handler;
    private TelephonyManager mTelephonyManager;
    private int mSubId;
    private final int mSlotIndex;
    private boolean mIsQnsListenerRegistered;
    private static final SparseArray<CellularQualityMonitor> sCellularQualityMonitors =
            new SparseArray<>();
    private final List<SignalThresholdInfo> mSignalThresholdInfoList;

    /**
     * thresholdMatrix stores the thresholds according to measurement type and apn type. For ex:
     * LTE_RSRP: {TYPE_IMS: [-112, -110, -90], TYPE_XCAP: [-100, -99]} LTE_RSSNR:{TYPE_IMS: [-10,
     * -15], TYPE_EMERGENCY: [-15]}
     */
    private final ConcurrentHashMap<String, SparseArray<List<Integer>>> mThresholdMatrix =
            new ConcurrentHashMap<>();

    private final HashMap<String, int[]> mThresholdsRegistered = new HashMap<>();
    private HashMap<String, Integer> mThresholdWaitTimer = new HashMap<>();
    private SignalStrengthUpdateRequest mSSUpdateRequest;
    private final CellularSignalStrengthListener mSignalStrengthListener;
    private final QnsTelephonyListener mQnsTelephonyListener;

    private CellularQualityMonitor(Context context, int slotIndex) {
        super(QualityMonitor.class.getSimpleName() + "-C-" + slotIndex);
        mTag = CellularQualityMonitor.class.getSimpleName() + "-" + slotIndex;
        mContext = context;
        mSlotIndex = slotIndex;
        mSubId = getSubId();
        mIsQnsListenerRegistered = false;
        mSignalThresholdInfoList = new ArrayList<>();
        HandlerThread handlerThread = new HandlerThread(mTag);
        handlerThread.start();
        handler = new CellularEventsHandler(handlerThread.getLooper());
        mTelephonyManager = mContext.getSystemService(TelephonyManager.class);
        mQnsTelephonyListener = QnsTelephonyListener.getInstance(context, slotIndex);
        mQnsTelephonyListener.registerSubscriptionIdListener(
                handler, EVENT_SUBSCRIPTION_ID_CHANGED, null);
        if (mTelephonyManager != null) {
            mTelephonyManager = mTelephonyManager.createForSubscriptionId(mSubId);
        } else {
            Log.e(mTag, "Failed to get Telephony Service");
        }
        mSignalStrengthListener = new CellularSignalStrengthListener(mContext.getMainExecutor());
        mSignalStrengthListener.setSignalStrengthListener(this::onSignalStrengthsChanged);
    }

    /** Listener for change of signal strength. */
    private interface OnSignalStrengthListener {
        /** Notify the cellular signal strength changed. */
        void onSignalStrengthsChanged(SignalStrength signalStrength);
    }

    public static QualityMonitor getInstance(Context context, int slotIndex) {
        CellularQualityMonitor cellularQualityMonitor = sCellularQualityMonitors.get(slotIndex);
        if (cellularQualityMonitor != null) {
            return cellularQualityMonitor;
        }
        cellularQualityMonitor = new CellularQualityMonitor(context, slotIndex);
        sCellularQualityMonitors.put(slotIndex, cellularQualityMonitor);
        return cellularQualityMonitor;
    }

    private int getSubId() {
        int[] subId = SubscriptionManager.getSubId(mSlotIndex);
        if (subId != null && subId.length > 0) {
            return subId[0];
        } else {
            return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        }
    }

    /** {@link TelephonyCallback} to listen to Cellular Service State Changed. */
    private class CellularSignalStrengthListener extends TelephonyCallback
            implements TelephonyCallback.SignalStrengthsListener {
        private OnSignalStrengthListener mSignalStrengthListener;
        private Executor mExecutor;

        public CellularSignalStrengthListener(Executor executor) {
            super();
            mExecutor = executor;
        }

        public void setSignalStrengthListener(OnSignalStrengthListener listener) {
            mSignalStrengthListener = listener;
        }

        /** Register a TelephonyCallback for this listener. */
        public void register() {
            long identity = Binder.clearCallingIdentity();
            try {
                mTelephonyManager.registerTelephonyCallback(mExecutor, this);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        /** Unregister a TelephonyCallback for this listener. */
        public void unregister() {
            mTelephonyManager.unregisterTelephonyCallback(this);
        }

        @Override
        public void onSignalStrengthsChanged(@NonNull SignalStrength signalStrength) {
            if (mSignalStrengthListener != null) {
                Log.d(mTag, "Signal Strength Changed : " + signalStrength);
                mSignalStrengthListener.onSignalStrengthsChanged(signalStrength);
            }
        }
    }

    private void onSignalStrengthsChanged(SignalStrength signalStrength) {
        List<CellSignalStrength> ss = signalStrength.getCellSignalStrengths();
        if (!ss.isEmpty()) {
            int accessNetwork = AccessNetworkType.UNKNOWN;
            for (CellSignalStrength cs : ss) {
                if (cs.isValid()) {
                    if (cs instanceof CellSignalStrengthNr) {
                        accessNetwork = AccessNetworkType.NGRAN;
                    } else if (cs instanceof CellSignalStrengthLte) {
                        accessNetwork = AccessNetworkType.EUTRAN;
                    } else if (cs instanceof CellSignalStrengthWcdma) {
                        accessNetwork = AccessNetworkType.UTRAN;
                    } else if (cs instanceof CellSignalStrengthCdma) {
                        accessNetwork = AccessNetworkType.CDMA2000;
                    } else if (cs instanceof CellSignalStrengthGsm) {
                        accessNetwork = AccessNetworkType.GERAN;
                    } else {
                        Log.d(mTag, "Unknown signal strength :" + cs);
                        continue;
                    }
                    checkAndNotifySignalStrength(cs, accessNetwork);
                }
            }
        }
    }

    private void checkAndNotifySignalStrength(
            CellSignalStrength cellSignalStrength, int accessNetwork) {
        Log.d(mTag, "CellSignalStrength Changed: " + cellSignalStrength);

        int signalStrength;
        for (Map.Entry<String, List<Threshold>> entry : mThresholdsList.entrySet()) {
            // check if key is in waiting list of backhaul
            if (mWaitingThresholds.getOrDefault(entry.getKey(), false)) {
                Log.d(mTag, "Backhaul timer already running for the threshold");
                continue;
            }
            List<Threshold> matchedThresholds = new ArrayList<>();
            Threshold threshold;
            for (Threshold th : entry.getValue()) {
                if (th.getAccessNetwork() != accessNetwork) continue;
                signalStrength =
                        getSignalStrength(
                                th.getAccessNetwork(), th.getMeasurementType(), cellSignalStrength);
                if (th.isMatching(signalStrength)) {
                    threshold = th.copy();
                    threshold.setThreshold(signalStrength);
                    matchedThresholds.add(threshold);
                }
            }
            if (matchedThresholds.size() > 0) {
                notifyThresholdChange(entry.getKey(), matchedThresholds.toArray(new Threshold[0]));
            }
        }
    }

    /**
     * Method checks the thresholds mapped with @param String key with current thresholds. If data
     * gets matched, thresholds will be notified.
     */
    private void checkAndNotifySignalStrength(String key) {
        List<Threshold> ths = mThresholdsList.get(key);
        int signalStrength;
        List<Threshold> matchedThresholds = new ArrayList<>();
        Threshold threshold;
        for (Threshold th : ths) {
            signalStrength = getCurrentQuality(th.getAccessNetwork(), th.getMeasurementType());
            if (th.isMatching(signalStrength)) {
                threshold = th.copy();
                threshold.setThreshold(signalStrength);
                matchedThresholds.add(threshold);
            }
        }
        if (matchedThresholds.size() > 0) {
            notifyThresholdChange(key, matchedThresholds.toArray(new Threshold[0]));
        }
    }

    @Override
    public synchronized int getCurrentQuality(int accessNetwork, int measurementType) {
        SignalStrength ss = mTelephonyManager.getSignalStrength();
        int quality = SignalStrength.INVALID; // Int Max Value
        if (ss != null) {
            List<CellSignalStrength> cellSignalStrengthList = ss.getCellSignalStrengths();
            for (CellSignalStrength cs : cellSignalStrengthList) {
                quality = getSignalStrength(accessNetwork, measurementType, cs);
                if (quality != CellInfo.UNAVAILABLE) {
                    return quality;
                }
            }
        }
        return quality;
    }

    @Override
    public synchronized void registerThresholdChange(
            ThresholdCallback thresholdCallback, int apnType, Threshold[] ths, int slotIndex) {
        Log.d(mTag, "registerThresholdChange for apnType= " + apnType);
        super.registerThresholdChange(thresholdCallback, apnType, ths, slotIndex);
        updateThresholdsForApn(apnType, slotIndex, ths);
    }

    @Override
    public synchronized void unregisterThresholdChange(int apnType, int slotIndex) {
        Log.d(mTag, "unregisterThresholdChange for apnType= " + apnType);
        super.unregisterThresholdChange(apnType, slotIndex);
        updateThresholdsMatrix(apnType, null);
        if (updateRegisteredThresholdsArray()) {
            createSignalThresholdsInfoList();
            listenRequests();
        }
    }

    @Override
    public synchronized void updateThresholdsForApn(int apnType, int slotIndex, Threshold[] ths) {
        Log.d(mTag, "updateThresholdsForApn for apnType= " + apnType);
        super.updateThresholdsForApn(apnType, slotIndex, ths);
        if (ths != null && ths.length > 0 && !validateThresholdList(ths)) {
            throw new IllegalStateException("Thresholds are not in valid range.");
        }
        updateThresholdsMatrix(apnType, ths);
        if (updateRegisteredThresholdsArray()) {
            createSignalThresholdsInfoList();
            listenRequests();
        }
    }

    @Override
    protected void notifyThresholdChange(String key, Threshold[] ths) {
        IThresholdListener listener = mThresholdCallbackMap.get(key);
        Log.d(mTag, "Notify Threshold Change to listener = " + listener);
        if (listener != null) {
            listener.onCellularThresholdChanged(ths);
        }
    }

    private void createSignalThresholdsInfoList() {
        mSignalThresholdInfoList.clear();
        for (Map.Entry<String, int[]> entry : mThresholdsRegistered.entrySet()) {
            if (entry.getValue().length == 0) continue;
            int networkType = Integer.parseInt(entry.getKey().split("_")[0]);
            int measurementType = Integer.parseInt(entry.getKey().split("_")[1]);
            if (measurementType == QnsConstants.SIGNAL_MEASUREMENT_TYPE_ECNO) {
                AlternativeEventListener.getInstance(mContext, mSlotIndex)
                        .setEcnoSignalThreshold(entry.getValue());
            } else {
                SignalThresholdInfo.Builder builder =
                        new SignalThresholdInfo.Builder()
                                .setRadioAccessNetworkType(networkType)
                                .setSignalMeasurementType(measurementType)
                                .setThresholds(entry.getValue())
                                .setIsEnabled(true);
                int backhaulTime = mThresholdWaitTimer.getOrDefault(entry.getKey(), -1);
                if (backhaulTime > 0) {
                    builder.setHysteresisMs(backhaulTime);
                }
                mSignalThresholdInfoList.add(builder.build());
                Log.d(mTag, "Updated SignalThresholdInfo List: " + mSignalThresholdInfoList);
            }
        }
    }

    private boolean updateRegisteredThresholdsArray() {
        boolean isUpdated = false;
        for (Map.Entry<String, SparseArray<List<Integer>>> entry : mThresholdMatrix.entrySet()) {
            SparseArray<List<Integer>> apnThresholds =
                    mThresholdMatrix.getOrDefault(entry.getKey(), new SparseArray<>());
            Set<Integer> thresholdsSet = new HashSet<>(); // to store unique thresholds
            int count = 0;
            for (int i = 0; (i < apnThresholds.size() && count <= MAX_THRESHOLD_COUNT); i++) {
                List<Integer> thresholdsList =
                        apnThresholds.get(apnThresholds.keyAt(i), new ArrayList<>());
                for (int t : thresholdsList) {
                    if (thresholdsSet.add(t)) {
                        count++;
                    }
                    if (count >= MAX_THRESHOLD_COUNT) {
                        break;
                    }
                }
            }
            int[] newThresholds = new int[thresholdsSet.size()];
            count = 0;
            for (int i : thresholdsSet) {
                newThresholds[count++] = i;
            }
            Arrays.sort(newThresholds);
            int[] oldThresholds = mThresholdsRegistered.get(entry.getKey());
            Log.d(
                    mTag,
                    "For measurement type= "
                            + entry.getKey()
                            + " old Threshold= "
                            + Arrays.toString(oldThresholds)
                            + " new Threshold= "
                            + Arrays.toString(newThresholds));
            if (!Arrays.equals(newThresholds, oldThresholds)) {
                mThresholdsRegistered.put(entry.getKey(), newThresholds);
                isUpdated = true;
            }
        }
        return isUpdated;
    }

    private void updateThresholdsMatrix(int apnType, Threshold[] ths) {

        Log.d(mTag, "Current threshold matrix: " + mThresholdMatrix);
        // clear old threshold for the apn type in given apn type from threshold matrix.
        for (Map.Entry<String, SparseArray<List<Integer>>> entry : mThresholdMatrix.entrySet()) {
            SparseArray<List<Integer>> apnThresholds = mThresholdMatrix.get(entry.getKey());
            if (apnThresholds != null) {
                apnThresholds.remove(apnType);
            }
        }
        if (ths == null || ths.length == 0) {
            return;
        }

        // store new thresholds in threshold matrix
        for (Threshold th : ths) {
            String key = th.getAccessNetwork() + "_" + th.getMeasurementType();
            SparseArray<List<Integer>> apnThresholds =
                    mThresholdMatrix.getOrDefault(key, new SparseArray<>());
            List<Integer> thresholdsList = apnThresholds.get(apnType, new ArrayList<>());
            thresholdsList.add(th.getThreshold());
            apnThresholds.put(apnType, thresholdsList);
            mThresholdMatrix.put(key, apnThresholds);
            mThresholdWaitTimer.put(key, th.getWaitTime());
        }
        Log.d(mTag, "updated thresholds matrix: " + mThresholdMatrix);
    }

    /** This methods stops listening for the thresholds. */
    private synchronized void clearOldRequests() {
        if (mSSUpdateRequest != null) {
            Log.d(mTag, "Clearing request: " + mSSUpdateRequest);
            mTelephonyManager.clearSignalStrengthUpdateRequest(mSSUpdateRequest);
            mSSUpdateRequest = null;
        }
        mSignalStrengthListener.unregister();
        AlternativeEventListener.getInstance(mContext, mSlotIndex).setEcnoSignalThreshold(null);
    }

    /** This methods starts listening for the thresholds. */
    private void listenRequests() {
        clearOldRequests();
        if (mSignalThresholdInfoList.size() > 0) {
            mSSUpdateRequest =
                    new SignalStrengthUpdateRequest.Builder()
                            .setSignalThresholdInfos(mSignalThresholdInfoList)
                            .setReportingRequestedWhileIdle(true)
                            .build();
            mTelephonyManager.setSignalStrengthUpdateRequest(mSSUpdateRequest);
            Log.d(mTag, "Listening to request: " + mSSUpdateRequest);
            mSignalStrengthListener.register();
            if (!mIsQnsListenerRegistered) {
                mQnsTelephonyListener.registerQnsTelephonyInfoChanged(
                        ApnSetting.TYPE_NONE,
                        handler,
                        EVENT_CELLULAR_QNS_TELEPHONY_INFO_CHANGED,
                        null,
                        false);
                mIsQnsListenerRegistered = true;
            }
        } else {
            Log.d(mTag, "No requests are pending to listen");
            mQnsTelephonyListener.unregisterQnsTelephonyInfoChanged(ApnSetting.TYPE_NONE, handler);
            mIsQnsListenerRegistered = false;
        }
    }

    private int getSignalStrength(int accessNetwork, int measurementType, CellSignalStrength css) {
        int signalStrength = SignalStrength.INVALID;
        switch (measurementType) {
            case SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSSI:
                if (accessNetwork == AccessNetworkType.UTRAN
                        && css instanceof CellSignalStrengthWcdma) {
                    signalStrength = ((CellSignalStrengthWcdma) css).getRssi();
                } else if (accessNetwork == AccessNetworkType.GERAN
                        && css instanceof CellSignalStrengthGsm) {
                    signalStrength = ((CellSignalStrengthGsm) css).getRssi();
                }
                break;
            case SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSCP:
                if (accessNetwork == AccessNetworkType.UTRAN
                        && css instanceof CellSignalStrengthWcdma) {
                    signalStrength = ((CellSignalStrengthWcdma) css).getRscp();
                }
                break;
            case SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSRP:
                if (accessNetwork == AccessNetworkType.EUTRAN
                        && css instanceof CellSignalStrengthLte) {
                    signalStrength = ((CellSignalStrengthLte) css).getRsrp();
                }
                break;
            case SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSRQ:
                if (accessNetwork == AccessNetworkType.EUTRAN
                        && css instanceof CellSignalStrengthLte) {
                    signalStrength = ((CellSignalStrengthLte) css).getRsrq();
                }
                break;
            case SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSSNR:
                if (accessNetwork == AccessNetworkType.EUTRAN
                        && css instanceof CellSignalStrengthLte) {
                    signalStrength = ((CellSignalStrengthLte) css).getRssnr();
                }
                break;
            case SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_SSRSRP:
                if (accessNetwork == AccessNetworkType.NGRAN
                        && css instanceof CellSignalStrengthNr) {
                    signalStrength = ((CellSignalStrengthNr) css).getSsRsrp();
                }
                break;
            case SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_SSRSRQ:
                if (accessNetwork == AccessNetworkType.NGRAN
                        && css instanceof CellSignalStrengthNr) {
                    signalStrength = ((CellSignalStrengthNr) css).getSsRsrq();
                }
                break;
            case SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_SSSINR:
                if (accessNetwork == AccessNetworkType.NGRAN
                        && css instanceof CellSignalStrengthNr) {
                    signalStrength = ((CellSignalStrengthNr) css).getSsSinr();
                }
                break;
            case QnsConstants.SIGNAL_MEASUREMENT_TYPE_ECNO:
                if (accessNetwork == AccessNetworkType.UTRAN
                        && css instanceof CellSignalStrengthWcdma) {
                    signalStrength = ((CellSignalStrengthWcdma) css).getEcNo();
                }
                break;
            default:
                Log.d(mTag, "measurement type = " + measurementType + " not handled.");
                break;
        }
        return signalStrength;
    }

    private boolean validateThresholdList(Threshold[] ths) {
        for (Threshold threshold : ths) {
            if (!isValidThreshold(threshold.getMeasurementType(), threshold.getThreshold())) {
                return false;
            }
        }
        return true;
    }

    /** Return true if signal measurement type is valid and the threshold value is in range. */
    private static boolean isValidThreshold(int type, int threshold) {
        switch (type) {
            case SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSSI:
                return threshold >= SignalThresholdInfo.SIGNAL_RSSI_MIN_VALUE
                        && threshold <= SignalThresholdInfo.SIGNAL_RSSI_MAX_VALUE;
            case SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSCP:
                return threshold >= SignalThresholdInfo.SIGNAL_RSCP_MIN_VALUE
                        && threshold <= SignalThresholdInfo.SIGNAL_RSCP_MAX_VALUE;
            case SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSRP:
                return threshold >= SignalThresholdInfo.SIGNAL_RSRP_MIN_VALUE
                        && threshold <= SignalThresholdInfo.SIGNAL_RSRP_MAX_VALUE;
            case SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSRQ:
                return threshold >= SignalThresholdInfo.SIGNAL_RSRQ_MIN_VALUE
                        && threshold <= SignalThresholdInfo.SIGNAL_RSRQ_MAX_VALUE;
            case SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSSNR:
                return threshold >= SignalThresholdInfo.SIGNAL_RSSNR_MIN_VALUE
                        && threshold <= SignalThresholdInfo.SIGNAL_RSSNR_MAX_VALUE;
            case SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_SSRSRP:
                return threshold >= SignalThresholdInfo.SIGNAL_SSRSRP_MIN_VALUE
                        && threshold <= SignalThresholdInfo.SIGNAL_SSRSRP_MAX_VALUE;
            case SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_SSRSRQ:
                return threshold >= SignalThresholdInfo.SIGNAL_SSRSRQ_MIN_VALUE
                        && threshold <= SignalThresholdInfo.SIGNAL_SSRSRQ_MAX_VALUE;
            case SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_SSSINR:
                return threshold >= SignalThresholdInfo.SIGNAL_SSSINR_MIN_VALUE
                        && threshold <= SignalThresholdInfo.SIGNAL_SSSINR_MAX_VALUE;
            case QnsConstants.SIGNAL_MEASUREMENT_TYPE_ECNO:
                return threshold >= QnsConstants.SIGNAL_ECNO_MIN_VALUE
                        && threshold <= QnsConstants.SIGNAL_ECNO_MAX_VALUE;

            default:
                return false;
        }
    }

    @VisibleForTesting
    public List<SignalThresholdInfo> getSignalThresholdInfo() {
        return mSignalThresholdInfoList;
    }

    private class CellularEventsHandler extends Handler {
        public CellularEventsHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            Log.d(mTag, "handleMessage what = " + msg.what);
            AsyncResult ar;
            switch (msg.what) {
                case EVENT_CELLULAR_QNS_TELEPHONY_INFO_CHANGED:
                    ar = (AsyncResult) msg.obj;
                    if (ar.exception == null
                            && ar.result instanceof QnsTelephonyListener.QnsTelephonyInfo) {
                        QnsTelephonyListener.QnsTelephonyInfo info =
                                (QnsTelephonyListener.QnsTelephonyInfo) ar.result;
                        onQnsTelephonyInfoChanged(info);
                    }
                    break;
                case EVENT_BACKHAUL_TIMER_EXPIRED:
                    String key = (String) msg.obj;
                    mWaitingThresholds.put(key, false);
                    // check and notify current thresholds info
                    checkAndNotifySignalStrength(key);
                    break;
                case EVENT_SUBSCRIPTION_ID_CHANGED:
                    ar = (AsyncResult) msg.obj;
                    int newSubId = (int) ar.result;
                    clearOldRequests();
                    mSubId = newSubId;
                    mTelephonyManager =
                            mContext.getSystemService(TelephonyManager.class)
                                    .createForSubscriptionId(mSubId);
                    break;
                default:
                    Log.d(mTag, "Not Handled !");
            }
        }

        QnsTelephonyListener.QnsTelephonyInfo mLastQnsTelephonyInfo = null;

        private void onQnsTelephonyInfoChanged(QnsTelephonyListener.QnsTelephonyInfo info) {
            if (mLastQnsTelephonyInfo == null
                    || mLastQnsTelephonyInfo.getDataTech() != info.getDataTech()
                    || mLastQnsTelephonyInfo.getDataRegState() != info.getDataRegState()
                    || mLastQnsTelephonyInfo.isCellularAvailable() != info.isCellularAvailable()) {
                if (!info.isCellularAvailable()) {
                    clearOldRequests();
                }
                mLastQnsTelephonyInfo = info;
            }
        }
    }

    @VisibleForTesting
    @Override
    public void dispose() {
        super.dispose();
        clearOldRequests();
        mSignalThresholdInfoList.clear();
        mIsQnsListenerRegistered = false;
        sCellularQualityMonitors.remove(mSlotIndex);
    }
}
