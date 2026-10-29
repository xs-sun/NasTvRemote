package com.tao7.nasremote;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.ContentResolver;
import android.content.Context;
import android.util.Log;

public class RevivalSync {
    private static final String TAG = "NasRemoteSync";
    private static final String AUTHORITY = "settings";

    public static void ensure(Context context) {
        try {
            String accountType = context.getPackageName();
            AccountManager accountManager = AccountManager.get(context);
            Account[] accounts = accountManager.getAccountsByType(accountType);
            Account account = (accounts != null && accounts.length > 0) ? accounts[0] : null;

            if (account == null) {
                account = new Account("NasTvRemote", accountType);
                if (accountManager.addAccountExplicitly(account, null, null)) {
                    Log.i(TAG, "Added account for settings sync revival");
                }
            }

            if (ContentResolver.getIsSyncable(account, AUTHORITY) <= 0) {
                ContentResolver.setIsSyncable(account, AUTHORITY, 1);
            }
            if (!ContentResolver.getSyncAutomatically(account, AUTHORITY)) {
                ContentResolver.setSyncAutomatically(account, AUTHORITY, true);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to ensure sync revival: " + e.getMessage());
        }
    }
}
