package com.example.ledger.fixture;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

/** Sends fixed synthetic samples on a dedicated emulator. Contains no real payment integration. */
public final class FixtureActivity extends Activity {
    private static final String CHANNEL_ID = "fixture";
    private static final String NOTIFICATION_TAG = "fixture";
    private static final String LOG_TAG = "LedgerNotificationFixture";

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.w(LOG_TAG, "Fixture notification permission has not been granted.");
                return;
            }
            String scenario = getIntent().getStringExtra("scenario");
            if (scenario == null) {
                Log.w(LOG_TAG, "A fixture scenario is required.");
                return;
            }
            postScenario(scenario);
        } finally {
            finish();
        }
    }

    private void postScenario(String scenario) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "轻记合成通知测试", NotificationManager.IMPORTANCE_DEFAULT));
        }

        String title = getPackageName().equals("com.tencent.mm") ? "微信支付" : "支付宝";
        String body;
        int id;
        long eventTime = System.currentTimeMillis();
        switch (scenario) {
            case "payment-order":
            case "payment-order2":
                id = scenario.endsWith("2") ? 107 : 106;
                body = "支付成功28.00元，商户：合成便利店\n" + providerOrderLabel() + "：" +
                        (scenario.endsWith("2") ? "SYN_PAY_0002" : "SYN_PAY_0001");
                break;
            case "refund-order":
            case "refund-order2":
            case "refund-over":
                id = scenario.endsWith("over") ? 110 : scenario.endsWith("2") ? 109 : 108;
                body = "退款成功" + (scenario.endsWith("over") ? "50.00" : scenario.endsWith("2") ? "3.00" : "5.00") +
                        "元\n退款单号：" + (scenario.endsWith("over") ? "SYN_REFUND_0003" : scenario.endsWith("2") ? "SYN_REFUND_0002" : "SYN_REFUND_0001") +
                        "\n原" + providerOrderLabel() + "：SYN_PAY_0001";
                break;
            case "payment1":
                id = 100;
                eventTime = firstPaymentTime();
                body = "支付成功12.30元";
                break;
            case "update":
                id = 100;
                eventTime = firstPaymentTime();
                body = "支付成功12.30元，商户：麦当劳";
                break;
            case "payment2":
                id = 101;
                body = "支付成功12.30元";
                break;
            case "income":
                id = 102;
                body = "收款到账100元";
                break;
            case "refund":
                id = 103;
                body = "退款成功5元";
                break;
            case "failed":
                id = 104;
                body = "支付失败20元";
                break;
            case "chat":
                id = 105;
                title = "张三";
                body = "支付成功10元";
                break;
            default:
                Log.w(LOG_TAG, "Unknown fixture scenario.");
                return;
        }

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setWhen(eventTime)
                .setShowWhen(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true);
        // No group or group-summary flag. Updates intentionally reuse tag, id, and event time.
        manager.notify(NOTIFICATION_TAG, id, builder.build());
    }

    private long firstPaymentTime() {
        SharedPreferences preferences = getSharedPreferences("fixture_events", MODE_PRIVATE);
        long stored = preferences.getLong("payment1_when", 0);
        if (stored > 0) return stored;
        long now = System.currentTimeMillis();
        preferences.edit().putLong("payment1_when", now).commit();
        return now;
    }

    private String providerOrderLabel() {
        return getPackageName().equals("com.tencent.mm") ? "微信支付交易单号" : "支付宝交易号";
    }
}
