package com.truelockmc.streambert;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(SecureStoragePlugin.class);
        registerPlugin(HiAnimePlugin.class);
        super.onCreate(savedInstanceState);
    }
}
