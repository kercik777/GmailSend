package com.gmail;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;

public final class NetworkUtil {

    private NetworkUtil() {
    }

    public static boolean isOnline(Context context) {
        ConnectivityManager manager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network network = manager.getActiveNetwork();
            if (network == null) {
                return false;
            }
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
            return capabilities != null
                    && (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN));
        }
        NetworkInfo info = manager.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }
}
