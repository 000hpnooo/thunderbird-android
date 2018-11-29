
package com.fsck.k9;


import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import android.content.Context;
import android.support.annotation.RestrictTo;
import android.support.annotation.RestrictTo.Scope;

import com.fsck.k9.backend.BackendManager;
import com.fsck.k9.mail.MessagingException;
import com.fsck.k9.mailstore.LocalStore;
import com.fsck.k9.preferences.Storage;
import com.fsck.k9.preferences.StorageEditor;
import timber.log.Timber;


public class Preferences {

    private static Preferences preferences;
    private AccountPreferenceSerializer accountPreferenceSerializer;

    public static synchronized Preferences getPreferences(Context context) {
        Context appContext = context.getApplicationContext();
        CoreResourceProvider resourceProvider = DI.get(CoreResourceProvider.class);
        LocalKeyStoreManager localKeyStoreManager = DI.get(LocalKeyStoreManager.class);
        AccountPreferenceSerializer accountPreferenceSerializer = DI.get(AccountPreferenceSerializer.class);
        if (preferences == null) {
            preferences = new Preferences(appContext, resourceProvider, localKeyStoreManager, accountPreferenceSerializer);
        }
        return preferences;
    }

    private Storage storage;
    private Map<String, Account> accounts = null;
    private List<Account> accountsInOrder = null;
    private Account newAccount;
    private Context context;
    private final CoreResourceProvider resourceProvider;
    private final LocalKeyStoreManager localKeyStoreManager;

    private Preferences(Context context, CoreResourceProvider resourceProvider,
            LocalKeyStoreManager localKeyStoreManager,
            AccountPreferenceSerializer accountPreferenceSerializer) {
        storage = Storage.getStorage(context);
        this.context = context;
        this.resourceProvider = resourceProvider;
        this.localKeyStoreManager = localKeyStoreManager;
        this.accountPreferenceSerializer = accountPreferenceSerializer;
        if (storage.isEmpty()) {
            Timber.i("Preferences storage is zero-size, importing from Android-style preferences");
            StorageEditor editor = storage.edit();
            editor.copy(context.getSharedPreferences("AndroidMail.Main", Context.MODE_PRIVATE));
            editor.commit();
        }
    }

    @RestrictTo(Scope.TESTS)
    public void clearAccounts() {
        accounts = new HashMap<>();
        accountsInOrder = new LinkedList<>();
    }

    public synchronized void loadAccounts() {
        accounts = new HashMap<>();
        accountsInOrder = new LinkedList<>();
        String accountUuids = getStorage().getString("accountUuids", null);
        if ((accountUuids != null) && (accountUuids.length() != 0)) {
            String[] uuids = accountUuids.split(",");
            for (String uuid : uuids) {
                Account newAccount = new Account(uuid);
                accountPreferenceSerializer.loadAccount(newAccount, storage);
                accounts.put(uuid, newAccount);
                accountsInOrder.add(newAccount);
            }
        }
        if ((newAccount != null) && newAccount.getAccountNumber() != -1) {
            accounts.put(newAccount.getUuid(), newAccount);
            if (!accountsInOrder.contains(newAccount)) {
                accountsInOrder.add(newAccount);
            }
            newAccount = null;
        }
    }

    /**
     * Returns an array of the accounts on the system. If no accounts are
     * registered the method returns an empty array.
     *
     * @return all accounts
     */
    public synchronized List<Account> getAccounts() {
        if (accounts == null) {
            loadAccounts();
        }

        return Collections.unmodifiableList(new ArrayList<>(accountsInOrder));
    }

    /**
     * Returns an array of the accounts on the system. If no accounts are
     * registered the method returns an empty array.
     *
     * @return all accounts with {@link Account#isAvailable(Context)}
     */
    public synchronized Collection<Account> getAvailableAccounts() {
        List<Account> allAccounts = getAccounts();
        Collection<Account> retval = new ArrayList<>(accounts.size());
        for (Account account : allAccounts) {
            if (account.isEnabled() && account.isAvailable(context)) {
                retval.add(account);
            }
        }

        return retval;
    }

    public synchronized Account getAccount(String uuid) {
        if (accounts == null) {
            loadAccounts();
        }

        return accounts.get(uuid);
    }

    public synchronized Account newAccount() {
        String accountUuid = UUID.randomUUID().toString();
        newAccount = new Account(accountUuid);
        accountPreferenceSerializer.loadDefaults(newAccount);
        accounts.put(newAccount.getUuid(), newAccount);
        accountsInOrder.add(newAccount);

        return newAccount;
    }

    public synchronized void deleteAccount(Account account) {
        if (accounts != null) {
            accounts.remove(account.getUuid());
        }
        if (accountsInOrder != null) {
            accountsInOrder.remove(account);
        }

        try {
            getBackendManager().removeBackend(account);
        } catch (Exception e) {
            Timber.e(e, "Failed to reset remote store for account %s", account.getUuid());
        }
        LocalStore.removeAccount(account);

        DI.get(AccountPreferenceSerializer.class).delete(storage, account);
        localKeyStoreManager.deleteCertificates(account);

        if (newAccount == account) {
            newAccount = null;
        }
    }

    /**
     * Returns the Account marked as default. If no account is marked as default
     * the first account in the list is marked as default and then returned. If
     * there are no accounts on the system the method returns null.
     */
    public Account getDefaultAccount() {
        String defaultAccountUuid = getStorage().getString("defaultAccountUuid", null);
        Account defaultAccount = getAccount(defaultAccountUuid);

        if (defaultAccount == null) {
            Collection<Account> accounts = getAvailableAccounts();
            if (!accounts.isEmpty()) {
                defaultAccount = accounts.iterator().next();
                setDefaultAccount(defaultAccount);
            }
        }

        return defaultAccount;
    }

    public void setDefaultAccount(Account account) {
        getStorage().edit().putString("defaultAccountUuid", account.getUuid()).commit();
    }

    public Storage getStorage() {
        return storage;
    }

    private BackendManager getBackendManager() {
        return DI.get(BackendManager.class);
    }

    public void saveAccount(Account account) {
        StorageEditor editor = storage.edit();

        if (!accounts.containsKey(account.getUuid())) {
            int accountNumber = generateAccountNumber();
            account.setAccountNumber(accountNumber);
        }

        processChangedValues(account);

        accountPreferenceSerializer.save(storage, editor, account);
    }

    private void processChangedValues(Account account) {
        if (account.isChangedVisibleLimits()) {
            try {
                account.getLocalStore().resetVisibleLimits(account.getDisplayCount());
            } catch (MessagingException e) {
                Timber.e(e, "Failed to load LocalStore!");
            }
        }

        if (account.isChangedLocalStorageProviderId()) {
            try {
                account.getLocalStore().switchLocalStorage(account.getLocalStorageProviderId());
            } catch (MessagingException e) {
                Timber.e(e, "Failed to load LocalStore!");
            }
        }

        account.resetChangeMarkers();
    }

    public int generateAccountNumber() {
        List<Integer> accountNumbers = getExistingAccountNumbers();
        return findNewAccountNumber(accountNumbers);
    }

    private List<Integer> getExistingAccountNumbers() {
        List<Account> accounts = getAccounts();
        List<Integer> accountNumbers = new ArrayList<>(accounts.size());
        for (Account a : accounts) {
            accountNumbers.add(a.getAccountNumber());
        }
        return accountNumbers;
    }

    private static int findNewAccountNumber(List<Integer> accountNumbers) {
        int newAccountNumber = -1;
        Collections.sort(accountNumbers);
        for (int accountNumber : accountNumbers) {
            if (accountNumber > newAccountNumber + 1) {
                break;
            }
            newAccountNumber = accountNumber;
        }
        newAccountNumber++;
        return newAccountNumber;
    }

    public void move(Account account, boolean mUp) {
        accountPreferenceSerializer.move(account, storage, mUp);
        loadAccounts();
    }
}
