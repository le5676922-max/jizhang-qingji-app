package com.example.ledger.fixture;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Synthetic receipt surfaces for a disposable emulator. No real payment or notification is sent. */
public final class TransferResultActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        showScenario(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        showScenario(intent);
    }

    private void showScenario(Intent intent) {
        String scenario = intent.getStringExtra("scenario");
        if (scenario == null) scenario = "screen-transfer";
        boolean secure = "screen-secure".equals(scenario);
        if (secure) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(Color.WHITE);
        content.setPadding(dp(24), dp(26), dp(24), dp(30));

        switch (scenario) {
            case "screen-transfer":
                title(content, "转账详情");
                line(content, "转账成功");
                line(content, "转账金额：12.30元");
                line(content, "收款人：测试用户");
                line(content, "交易单号：SCREEN_TRANSFER_001");
                break;
            case "screen-transfer2":
                title(content, "转账详情");
                line(content, "转账成功");
                line(content, "转账金额：12.30元");
                line(content, "收款人：测试用户");
                line(content, "交易单号：SCREEN_TRANSFER_002");
                break;
            case "screen-redpacket":
                title(content, "红包详情");
                line(content, "红包已发出");
                line(content, "红包总金额：5.00元");
                line(content, "待领取");
                line(content, "红包单号：SCREEN_RED_PACKET_01");
                break;
            case "screen-ocr":
            case "screen-secure":
                // Only this title is exposed to accessibility. Result/amount/ID are raster text.
                title(content, "转账详情");
                String receiptId = secure ? "DIFFERENT_SECURE_001" : "SCREEN_OCR_001";
                ReceiptCanvas receipt = new ReceiptCanvas(this, receiptId);
                content.addView(receipt, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(330)));
                break;
            case "screen-chat":
                title(content, "聊天信息");
                line(content, "转账成功");
                line(content, "转账金额：66.66元");
                EditText message = new EditText(this);
                message.setSingleLine(true);
                message.setHint("输入聊天消息");
                message.setTextColor(Color.BLACK);
                message.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
                content.addView(message, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(58)));
                // Keep the editable node visible, without showing the keyboard over the receipt.
                content.setFocusableInTouchMode(true);
                content.requestFocus();
                break;
            default:
                title(content, "未知测试场景");
                line(content, "请使用 README 中列出的固定场景。");
                break;
        }

        TextView disclaimer = new TextView(this);
        disclaimer.setText("轻记测试夹具 · 合成界面 · 仅测试模拟器");
        disclaimer.setTextSize(13);
        disclaimer.setTextColor(Color.GRAY);
        LinearLayout.LayoutParams disclaimerLayout = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        disclaimerLayout.topMargin = dp(28);
        content.addView(disclaimer, disclaimerLayout);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(content);
        setContentView(scroll);
        // Updating a singleTop activity also needs a real content event for the capture engine.
        content.postDelayed(() -> content.sendAccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED), 700);
        content.postDelayed(() -> content.sendAccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED), 1500);
    }

    private void title(LinearLayout content, String text) {
        TextView title = new TextView(this);
        title.setText(text);
        title.setTextSize(30);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(Color.BLACK);
        title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(28);
        content.addView(title, params);
    }

    private void line(LinearLayout content, String text) {
        TextView row = new TextView(this);
        row.setText(text);
        row.setTextSize(text.startsWith("交易单号") || text.startsWith("红包单号") ? 17 : 23);
        row.setTextColor(Color.BLACK);
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(20);
        content.addView(row, params);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class ReceiptCanvas extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String receiptId;
        private final float density;
        private final float scaledDensity;

        ReceiptCanvas(Context context, String receiptId) {
            super(context);
            this.receiptId = receiptId;
            density = getResources().getDisplayMetrics().density;
            scaledDensity = getResources().getDisplayMetrics().scaledDensity;
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            setContentDescription(null);
            setFocusable(false);
            paint.setColor(Color.BLACK);
            paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawColor(Color.WHITE);
            drawLine(canvas, "转账成功", 40, 30);
            drawLine(canvas, "转账金额：28.56元", 105, 27);
            drawLine(canvas, "收款人：测试用户", 165, 23);
            drawLine(canvas, "交易单号", 225, 22);
            drawLine(canvas, receiptId, 267, 19);
        }

        private void drawLine(Canvas canvas, String text, int yDp, int sp) {
            paint.setTextSize(sp * scaledDensity);
            canvas.drawText(text, 0, yDp * density, paint);
        }
    }
}
