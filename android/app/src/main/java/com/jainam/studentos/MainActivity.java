package com.jainam.studentos;

import android.content.Intent;
import android.os.Bundle;
import com.getcapacitor.BridgeActivity;
import com.jainam.studentos.alarm.AlarmPlugin;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        initialPlugins.add(DeepLockPlugin.class);
        initialPlugins.add(AlarmPlugin.class);
        super.onCreate(savedInstanceState);
        handleNavigateIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleNavigateIntent(intent);
    }

    private void handleNavigateIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getStringExtra("navigate_to");
        if ("focus".equals(action) && getBridge() != null && getBridge().getWebView() != null) {
            getBridge().getWebView().evaluateJavascript(
                "window.__navigateTo && window.__navigateTo('focus')", null);
        }
    }
}
