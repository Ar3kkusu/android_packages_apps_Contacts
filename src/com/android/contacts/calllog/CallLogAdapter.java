/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.contacts.calllog;

import com.android.common.widget.GroupingListAdapter;
import com.android.contacts.ContactPhotoManager;
import com.android.contacts.PhoneCallDetails;
import com.android.contacts.PhoneCallDetailsHelper;
import com.android.contacts.R;
import com.android.contacts.util.ExpirableCache;
import com.google.common.annotations.VisibleForTesting;

import android.content.Context;
import android.content.res.Resources;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.provider.CallLog.Calls;
import android.provider.ContactsContract.CommonDataKinds.SipAddress;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.PhoneLookup;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.util.LinkedList;

/**
 * Adapter class to fill in data for the Call Log.
 */
public final class CallLogAdapter extends GroupingListAdapter
        implements Runnable, ViewTreeObserver.OnPreDrawListener, CallLogGroupBuilder.GroupCreator {
    /** Interface used to initiate a refresh of the content. */
    public interface CallFetcher {
        public void startCallsQuery();
    }

    /** The time in millis to delay starting the thread processing requests. */
    private static final int START_PROCESSING_REQUESTS_DELAY_MILLIS = 1000;

    /** The size of the cache of contact info. */
    private static final int CONTACT_INFO_CACHE_SIZE = 100;

    private final Context mContext;
    private final String mCurrentCountryIso;
    private final CallFetcher mCallFetcher;

    /**
     * A cache of the contact details for the phone numbers in the call log.
     * <p>
     * The content of the cache is expired (but not purged) whenever the application comes to
     * the foreground.
     */
    private ExpirableCache<String, ContactInfo> mContactInfoCache;

    /**
     * List of requests to update contact details.
     * <p>
     * The requests are added when displaying the contacts and are processed by a background
     * thread.
     */
    private final LinkedList<String> mRequests;

    private volatile boolean mDone;
    private boolean mLoading = true;
    private ViewTreeObserver.OnPreDrawListener mPreDrawListener;
    private static final int REDRAW = 1;
    private static final int START_THREAD = 2;
    private boolean mFirst;
    private Thread mCallerIdThread;

    /** Instance of helper class for managing views. */
    private final CallLogListItemHelper mCallLogViewsHelper;

    /** Helper to set up contact photos. */
    private final ContactPhotoManager mContactPhotoManager;
    /** Helper to parse and process phone numbers. */
    private PhoneNumberHelper mPhoneNumberHelper;
    /** Helper to group call log entries. */
    private final CallLogGroupBuilder mCallLogGroupBuilder;

    /** Can be set to true by tests to disable processing of requests. */
    private volatile boolean mRequestProcessingDisabled = false;

    /** Listener for the primary action in the list, opens the call details. */
    private final View.OnClickListener mPrimaryActionListener = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            IntentProvider intentProvider = (IntentProvider) view.getTag();
            if (intentProvider != null) {
                mContext.startActivity(intentProvider.getIntent(mContext));
            }
        }
    };
    /** Listener for the secondary action in the list, either call or play. */
    private final View.OnClickListener mSecondaryActionListener = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            IntentProvider intentProvider = (IntentProvider) view.getTag();
            if (intentProvider != null) {
                mContext.startActivity(intentProvider.getIntent(mContext));
            }
        }
    };

    @Override
    public boolean onPreDraw() {
        if (mFirst) {
            mHandler.sendEmptyMessageDelayed(START_THREAD,
                    START_PROCESSING_REQUESTS_DELAY_MILLIS);
            mFirst = false;
        }
        return true;
    }

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case REDRAW:
                    notifyDataSetChanged();
                    break;
                case START_THREAD:
                    startRequestProcessing();
                    break;
            }
        }
    };

    public CallLogAdapter(Context context, CallFetcher callFetcher,
            String currentCountryIso, String voicemailNumber) {
        super(context);

        mContext = context;
        mCurrentCountryIso = currentCountryIso;
        mCallFetcher = callFetcher;

        mContactInfoCache = ExpirableCache.create(CONTACT_INFO_CACHE_SIZE);
        mRequests = new LinkedList<String>();
        mPreDrawListener = null;

        Resources resources = mContext.getResources();
        CallTypeHelper callTypeHelper = new CallTypeHelper(resources);

        mContactPhotoManager = ContactPhotoManager.getInstance(mContext);
        mPhoneNumberHelper = new PhoneNumberHelper(resources, voicemailNumber);
        PhoneCallDetailsHelper phoneCallDetailsHelper = new PhoneCallDetailsHelper(
                resources, callTypeHelper, mPhoneNumberHelper);
        mCallLogViewsHelper =
                new CallLogListItemHelper(
                        phoneCallDetailsHelper, mPhoneNumberHelper, resources);
        mCallLogGroupBuilder = new CallLogGroupBuilder(this);
    }

    /**
     * Requery on background thread when {@link Cursor} changes.
     */
    @Override
    protected void onContentChanged() {
        // When the content changes, always fetch all the calls, in case a new missed call came
        // in and we were filtering over voicemail only, so that we see the missed call.
        mCallFetcher.startCallsQuery();
    }

    void setLoading(boolean loading) {
        mLoading = loading;
    }

    @Override
    public boolean isEmpty() {
        if (mLoading) {
            // We don't want the empty state to show when loading.
            return false;
        } else {
            return super.isEmpty();
        }
    }

    public ContactInfo getContactInfo(String number) {
        return mContactInfoCache.getPossiblyExpired(number);
    }

    public void startRequestProcessing() {
        if (mRequestProcessingDisabled) {
            return;
        }

        mDone = false;
        mCallerIdThread = new Thread(this, "CallLogContactLookup");
        mCallerIdThread.setPriority(Thread.MIN_PRIORITY);
        mCallerIdThread.start();
    }

    /**
     * Stops the background thread that processes updates and cancels any pending requests to
     * start it.
     * <p>
     * Should be called from the main thread to prevent a race condition between the request to
     * start the thread being processed and stopping the thread.
     */
    public void stopRequestProcessing() {
        // Remove any pending requests to start the processing thread.
        mHandler.removeMessages(START_THREAD);
        mDone = true;
        if (mCallerIdThread != null) mCallerIdThread.interrupt();
    }

    public void invalidateCache() {
        mContactInfoCache.expireAll();
        // Let it restart the thread after next draw
        mPreDrawListener = null;
    }

    private void enqueueRequest(String number, boolean immediate) {
        synchronized (mRequests) {
            if (!mRequests.contains(number)) {
                mRequests.add(number);
                mRequests.notifyAll();
            }
        }
        if (mFirst && immediate) {
            startRequestProcessing();
            mFirst = false;
        }
    }

    /**
     * Determines the contact information for the given SIP address.
     * <p>
     * It returns the contact info if found.
     * <p>
     * If no contact corresponds to the given SIP address, returns {@link ContactInfo#EMPTY}.
     * <p>
     * If the lookup fails for some other reason, it returns null.
     */
    private ContactInfo queryContactInfoForSipAddress(String sipAddress) {
        final ContactInfo info;

        // TODO: This code is duplicated from the
        // CallerInfoAsyncQuery class.  To avoid that, could the
        // code here just use CallerInfoAsyncQuery, rather than
        // manually running ContentResolver.query() itself?

        // We look up SIP addresses directly in the Data table:
        Uri contactRef = Data.CONTENT_URI;

        // Note Data.DATA1 and SipAddress.SIP_ADDRESS are equivalent.
        //
        // Also note we use "upper(data1)" in the WHERE clause, and
        // uppercase the incoming SIP address, in order to do a
        // case-insensitive match.
        //
        // TODO: May also need to normalize by adding "sip:" as a
        // prefix, if we start storing SIP addresses that way in the
        // database.
        String selection = "upper(" + Data.DATA1 + ")=?"
                + " AND "
                + Data.MIMETYPE + "='" + SipAddress.CONTENT_ITEM_TYPE + "'";
        String[] selectionArgs = new String[] { sipAddress.toUpperCase() };

        Cursor dataTableCursor =
                mContext.getContentResolver().query(
                        contactRef,
                        null,  // projection
                        selection,  // selection
                        selectionArgs,  // selectionArgs
                        null);  // sortOrder

        if (dataTableCursor != null) {
            if (dataTableCursor.moveToFirst()) {
                info = new ContactInfo();

                // TODO: we could slightly speed this up using an
                // explicit projection (and thus not have to do
                // those getColumnIndex() calls) but the benefit is
                // very minimal.

                // Note the Data.CONTACT_ID column here is
                // equivalent to the PERSON_ID_COLUMN_INDEX column
                // we use with "phonesCursor" below.
                info.personId = dataTableCursor.getLong(
                        dataTableCursor.getColumnIndex(Data.CONTACT_ID));
                info.name = dataTableCursor.getString(
                        dataTableCursor.getColumnIndex(Data.DISPLAY_NAME));
                // "type" and "label" are currently unused for SIP addresses
                info.type = SipAddress.TYPE_OTHER;
                info.label = null;

                // And "number" is the SIP address.
                // Note Data.DATA1 and SipAddress.SIP_ADDRESS are equivalent.
                info.number = dataTableCursor.getString(
                        dataTableCursor.getColumnIndex(Data.DATA1));
                info.normalizedNumber = null;  // meaningless for SIP addresses
                final String thumbnailUriString = dataTableCursor.getString(
                        dataTableCursor.getColumnIndex(Data.PHOTO_THUMBNAIL_URI));
                info.thumbnailUri = thumbnailUriString == null
                        ? null
                        : Uri.parse(thumbnailUriString);
                info.lookupKey = dataTableCursor.getString(
                        dataTableCursor.getColumnIndex(Data.LOOKUP_KEY));
            } else {
                info = ContactInfo.EMPTY;
            }
            dataTableCursor.close();
        } else {
            // Failed to fetch the data, ignore this request.
            info = null;
        }
        return info;
    }

    /**
     * Determines the contact information for the given phone number.
     * <p>
     * It returns the contact info if found.
     * <p>
     * If no contact corresponds to the given phone number, returns {@link ContactInfo#EMPTY}.
     * <p>
     * If the lookup fails for some other reason, it returns null.
     */
    private ContactInfo queryContactInfoForPhoneNumber(String number) {
        final ContactInfo info;

        // "number" is a regular phone number, so use the
        // PhoneLookup table:
        Cursor phonesCursor =
                mContext.getContentResolver().query(
                    Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI,
                            Uri.encode(number)),
                            PhoneQuery._PROJECTION, null, null, null);
        if (phonesCursor != null) {
            if (phonesCursor.moveToFirst()) {
                info = new ContactInfo();
                info.personId = phonesCursor.getLong(PhoneQuery.PERSON_ID);
                info.name = phonesCursor.getString(PhoneQuery.NAME);
                info.type = phonesCursor.getInt(PhoneQuery.PHONE_TYPE);
                info.label = phonesCursor.getString(PhoneQuery.LABEL);
                info.number = phonesCursor
                        .getString(PhoneQuery.MATCHED_NUMBER);
                info.normalizedNumber = phonesCursor
                        .getString(PhoneQuery.NORMALIZED_NUMBER);
                final String thumbnailUriString = phonesCursor.getString(
                        PhoneQuery.THUMBNAIL_URI);
                info.thumbnailUri = thumbnailUriString == null
                        ? null
                        : Uri.parse(thumbnailUriString);
                info.lookupKey = phonesCursor.getString(PhoneQuery.LOOKUP_KEY);
            } else {
                info = ContactInfo.EMPTY;
            }
            phonesCursor.close();
        } else {
            // Failed to fetch the data, ignore this request.
            info = null;
        }
        return info;
    }

    /**
     * Queries the appropriate content provider for the contact associated with the number.
     * <p>
     * The number might be either a SIP address or a phone number.
     * <p>
     * It returns true if it updated the content of the cache and we should therefore tell the
     * view to update its content.
     */
    private boolean queryContactInfo(String number) {
        final ContactInfo info;

        // Determine the contact info.
        if (PhoneNumberUtils.isUriNumber(number)) {
            // This "number" is really a SIP address.
            info = queryContactInfoForSipAddress(number);
        } else {
            info = queryContactInfoForPhoneNumber(number);
        }

        if (info == null) {
            // The lookup failed, just return without requesting to update the view.
            return false;
        }

        // Check the existing entry in the cache: only if it has changed we should update the
        // view.
        ContactInfo existingInfo = mContactInfoCache.getPossiblyExpired(number);
        boolean updated = !info.equals(existingInfo);
        if (updated) {
            // The formattedNumber is computed by the UI thread when needed. Since we updated
            // the details of the contact, set this value to null for now.
            info.formattedNumber = null;
        }
        // Store the data in the cache so that the UI thread can use to display it. Store it
        // even if it has not changed so that it is marked as not expired.
        mContactInfoCache.put(number, info);
        return updated;
    }

    /*
     * Handles requests for contact name and number type
     * @see java.lang.Runnable#run()
     */
    @Override
    public void run() {
        boolean needNotify = false;
        while (!mDone) {
            String number = null;
            synchronized (mRequests) {
                if (!mRequests.isEmpty()) {
                    number = mRequests.removeFirst();
                } else {
                    if (needNotify) {
                        needNotify = false;
                        mHandler.sendEmptyMessage(REDRAW);
                    }
                    try {
                        mRequests.wait(1000);
                    } catch (InterruptedException ie) {
                        // Ignore and continue processing requests
                        Thread.currentThread().interrupt();
                    }
                }
            }
            if (!mDone && number != null && queryContactInfo(number)) {
                needNotify = true;
            }
        }
    }

    @Override
    protected void addGroups(Cursor cursor) {
        mCallLogGroupBuilder.addGroups(cursor);
    }

    @VisibleForTesting
    @Override
    public View newStandAloneView(Context context, ViewGroup parent) {
        LayoutInflater inflater =
                (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View view = inflater.inflate(R.layout.call_log_list_item, parent, false);
        findAndCacheViews(view);
        return view;
    }

    @VisibleForTesting
    @Override
    public void bindStandAloneView(View view, Context context, Cursor cursor) {
        bindView(view, cursor, 1);
    }

    @VisibleForTesting
    @Override
    public View newChildView(Context context, ViewGroup parent) {
        LayoutInflater inflater =
                (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View view = inflater.inflate(R.layout.call_log_list_item, parent, false);
        findAndCacheViews(view);
        return view;
    }

    @VisibleForTesting
    @Override
    public void bindChildView(View view, Context context, Cursor cursor) {
        bindView(view, cursor, 1);
    }

    @VisibleForTesting
    @Override
    public View newGroupView(Context context, ViewGroup parent) {
        LayoutInflater inflater =
                (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View view = inflater.inflate(R.layout.call_log_list_item, parent, false);
        findAndCacheViews(view);
        return view;
    }

    @VisibleForTesting
    @Override
    public void bindGroupView(View view, Context context, Cursor cursor, int groupSize,
            boolean expanded) {
        bindView(view, cursor, groupSize);
    }

    private void findAndCacheViews(View view) {
        // Get the views to bind to.
        CallLogListItemViews views = CallLogListItemViews.fromView(view);
        views.primaryActionView.setOnClickListener(mPrimaryActionListener);
        views.secondaryActionView.setOnClickListener(mSecondaryActionListener);
        view.setTag(views);
    }

    /**
     * Binds the views in the entry to the data in the call log.
     *
     * @param view the view corresponding to this entry
     * @param c the cursor pointing to the entry in the call log
     * @param count the number of entries in the current item, greater than 1 if it is a group
     */
    private void bindView(View view, Cursor c, int count) {
        final CallLogListItemViews views = (CallLogListItemViews) view.getTag();
        final int section = c.getInt(CallLogQuery.SECTION);

        // This might be a header: check the value of the section column in the cursor.
        if (section == CallLogQuery.SECTION_NEW_HEADER
                || section == CallLogQuery.SECTION_OLD_HEADER) {
            views.listItemView.setVisibility(View.GONE);
            views.listHeaderView.setVisibility(View.VISIBLE);
            views.listHeaderTextView.setText(
                    section == CallLogQuery.SECTION_NEW_HEADER
                            ? R.string.call_log_new_header
                            : R.string.call_log_old_header);
            // Nothing else to set up for a header.
            return;
        }
        // Default case: an item in the call log.
        views.listItemView.setVisibility(View.VISIBLE);
        views.listHeaderView.setVisibility(View.GONE);

        final String number = c.getString(CallLogQuery.NUMBER);
        final long date = c.getLong(CallLogQuery.DATE);
        final long duration = c.getLong(CallLogQuery.DURATION);
        final int callType = c.getInt(CallLogQuery.CALL_TYPE);
        final String formattedNumber;
        final String countryIso = c.getString(CallLogQuery.COUNTRY_ISO);

        views.primaryActionView.setTag(
                IntentProvider.getCallDetailIntentProvider(
                        this, c.getPosition(), c.getLong(CallLogQuery.ID), count));
        // Store away the voicemail information so we can play it directly.
        if (callType == Calls.VOICEMAIL_TYPE) {
            String voicemailUri = c.getString(CallLogQuery.VOICEMAIL_URI);
            final long rowId = c.getLong(CallLogQuery.ID);
            views.secondaryActionView.setTag(
                    IntentProvider.getPlayVoicemailIntentProvider(rowId, voicemailUri));
        } else if (!TextUtils.isEmpty(number)) {
            // Store away the number so we can call it directly if you click on the call icon.
            views.secondaryActionView.setTag(
                    IntentProvider.getReturnCallIntentProvider(number));
        } else {
            // No action enabled.
            views.secondaryActionView.setTag(null);
        }

        // Lookup contacts with this number
        ExpirableCache.CachedValue<ContactInfo> cachedInfo =
                mContactInfoCache.getCachedValue(number);
        ContactInfo info = cachedInfo == null ? null : cachedInfo.getValue();
        if (cachedInfo == null) {
            // Mark it as empty and queue up a request to find the name.
            // The db request should happen on a non-UI thread.
            info = ContactInfo.EMPTY;
            mContactInfoCache.put(number, info);
            // Request the contact details immediately since they are currently missing.
            enqueueRequest(number, true);
            // Format the phone number in the call log as best as we can.
            formattedNumber = formatPhoneNumber(number, null, countryIso);
        } else {
            if (cachedInfo.isExpired()) {
                // The contact info is no longer up to date, we should request it. However, we
                // do not need to request them immediately.
                enqueueRequest(number, false);
            }

            if (info != ContactInfo.EMPTY) {
                // Format and cache phone number for found contact.
                if (info.formattedNumber == null) {
                    info.formattedNumber =
                            formatPhoneNumber(info.number, info.normalizedNumber, countryIso);
                }
                formattedNumber = info.formattedNumber;
            } else {
                // Format the phone number in the call log as best as we can.
                formattedNumber = formatPhoneNumber(number, null, countryIso);
            }
        }

        final long personId = info.personId;
        final String name = info.name;
        final int ntype = info.type;
        final String label = info.label;
        final Uri thumbnailUri = info.thumbnailUri;
        final String lookupKey = info.lookupKey;
        final int[] callTypes = getCallTypes(c, count);
        final String geocode = c.getString(CallLogQuery.GEOCODED_LOCATION);
        final PhoneCallDetails details;
        if (TextUtils.isEmpty(name)) {
            details = new PhoneCallDetails(number, formattedNumber, countryIso, geocode,
                    callTypes, date, duration);
        } else {
            details = new PhoneCallDetails(number, formattedNumber, countryIso, geocode,
                    callTypes, date, duration, name, ntype, label, personId, thumbnailUri);
        }

        final boolean isNew = CallLogQuery.isNewSection(c);
        // New items also use the highlighted version of the text.
        final boolean isHighlighted = isNew;
        mCallLogViewsHelper.setPhoneCallDetails(views, details, isHighlighted);
        setPhoto(views, thumbnailUri, personId, lookupKey);

        // Listen for the first draw
        if (mPreDrawListener == null) {
            mFirst = true;
            mPreDrawListener = this;
            view.getViewTreeObserver().addOnPreDrawListener(this);
        }
    }

    /**
     * Returns the call types for the given number of items in the cursor.
     * <p>
     * It uses the next {@code count} rows in the cursor to extract the types.
     * <p>
     * It position in the cursor is unchanged by this function.
     */
    private int[] getCallTypes(Cursor cursor, int count) {
        int position = cursor.getPosition();
        int[] callTypes = new int[count];
        for (int index = 0; index < count; ++index) {
            callTypes[index] = cursor.getInt(CallLogQuery.CALL_TYPE);
            cursor.moveToNext();
        }
        cursor.moveToPosition(position);
        return callTypes;
    }

    private void setPhoto(CallLogListItemViews views, Uri thumbnailUri, long contactId,
            String lookupKey) {
        views.quickContactView.assignContactUri(contactId == -1 ? null :
                Contacts.getLookupUri(contactId, lookupKey));
        mContactPhotoManager.loadPhoto(views.quickContactView, thumbnailUri);
    }

    /**
     * Sets whether processing of requests for contact details should be enabled.
     * <p>
     * This method should be called in tests to disable such processing of requests when not
     * needed.
     */
    public void disableRequestProcessingForTest() {
        mRequestProcessingDisabled = true;
    }

    public void injectContactInfoForTest(String number, ContactInfo contactInfo) {
        mContactInfoCache.put(number, contactInfo);
    }

    @Override
    public void addGroup(int cursorPosition, int size, boolean expanded) {
        super.addGroup(cursorPosition, size, expanded);
    }

    /**
     * Format the given phone number
     *
     * @param number the number to be formatted.
     * @param normalizedNumber the normalized number of the given number.
     * @param countryIso the ISO 3166-1 two letters country code, the country's
     *        convention will be used to format the number if the normalized
     *        phone is null.
     *
     * @return the formatted number, or the given number if it was formatted.
     */
    private String formatPhoneNumber(String number, String normalizedNumber,
            String countryIso) {
        if (TextUtils.isEmpty(number)) {
            return "";
        }
        // If "number" is really a SIP address, don't try to do any formatting at all.
        if (PhoneNumberUtils.isUriNumber(number)) {
            return number;
        }
        if (TextUtils.isEmpty(countryIso)) {
            countryIso = mCurrentCountryIso;
        }
        return PhoneNumberUtils.formatNumber(number, normalizedNumber, countryIso);
    }

    /*
     * Get the number from the Contacts, if available, since sometimes
     * the number provided by caller id may not be formatted properly
     * depending on the carrier (roaming) in use at the time of the
     * incoming call.
     * Logic : If the caller-id number starts with a "+", use it
     *         Else if the number in the contacts starts with a "+", use that one
     *         Else if the number in the contacts is longer, use that one
     */
    public String getBetterNumberFromContacts(String number) {
        String matchingNumber = null;
        // Look in the cache first. If it's not found then query the Phones db
        ContactInfo ci = mContactInfoCache.getPossiblyExpired(number);
        if (ci != null && ci != ContactInfo.EMPTY) {
            matchingNumber = ci.number;
        } else {
            try {
                Cursor phonesCursor = mContext.getContentResolver().query(
                        Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, number),
                        PhoneQuery._PROJECTION, null, null, null);
                if (phonesCursor != null) {
                    if (phonesCursor.moveToFirst()) {
                        matchingNumber = phonesCursor.getString(PhoneQuery.MATCHED_NUMBER);
                    }
                    phonesCursor.close();
                }
            } catch (Exception e) {
                // Use the number from the call log
            }
        }
        if (!TextUtils.isEmpty(matchingNumber) &&
                (matchingNumber.startsWith("+")
                        || matchingNumber.length() > number.length())) {
            number = matchingNumber;
        }
        return number;
    }
}
