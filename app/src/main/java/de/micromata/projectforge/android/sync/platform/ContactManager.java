/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package de.micromata.projectforge.android.sync.platform;

import android.accounts.Account;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.CommonDataKinds.Note;
import android.provider.ContactsContract.CommonDataKinds.Organization;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.Photo;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal;
import android.provider.ContactsContract.CommonDataKinds.Website;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.Groups;
import android.provider.ContactsContract.RawContacts;
import android.provider.ContactsContract.Settings;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.micromata.projectforge.android.sync.Constants;
import de.micromata.projectforge.android.sync.client.RawAddress;
import de.micromata.projectforge.android.sync.client.RawContact;

/**
 * Class for managing contacts sync related mOperations
 */
public class ContactManager {

    /**
     * Custom IM protocol used when storing status messages.
     */
    public static final String CUSTOM_IM_PROTOCOL = "ProjectForgeSyncAdapter";

    private static final String TAG = "ContactManager";

    /**
     * The constant GROUP_NAME.
     */
    public static final String GROUP_NAME = "ProjectForge";

    /**
     * Ensure project forge group exists long.
     *
     * @param context the context
     * @param account the account
     * @return the long
     */
    public static long ensureProjectForgeGroupExists(Context context,
                                                     Account account) {
        final ContentResolver resolver = context.getContentResolver();

        // Lookup the sample group
        long groupId = 0;
        final Cursor cursor = resolver.query(Groups.CONTENT_URI,
                new String[]{Groups._ID},
                Groups.ACCOUNT_NAME + "=? AND " + Groups.ACCOUNT_TYPE
                        + "=? AND " + Groups.TITLE + "=?", new String[]{
                        account.name, account.type, GROUP_NAME}, null);
        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) {
                    groupId = cursor.getLong(0);
                }
            } finally {
                cursor.close();
            }
        }

        if (groupId == 0) {
            // Sample group doesn't exist yet, so create it
            final ContentValues contentValues = new ContentValues();
            contentValues.put(Groups.ACCOUNT_NAME, account.name);
            contentValues.put(Groups.ACCOUNT_TYPE, account.type);
            contentValues.put(Groups.TITLE, GROUP_NAME);
            contentValues.put(Groups.GROUP_IS_READ_ONLY, 1);

            final Uri newGroupUri = resolver.insert(Groups.CONTENT_URI,
                    contentValues);
            groupId = ContentUris.parseId(newGroupUri);
        }
        return groupId;
    }

    /**
     * Take a list of updated contacts and apply those changes to the contacts
     * database. Typically this list of contacts would have been returned from
     * the server, and we want to apply those changes locally.
     *
     * @param context        The context of Authenticator Activity
     * @param account        The username for the account
     * @param rawContacts    The list of contacts to update
     * @param groupId        the group id
     * @param lastSyncMarker The previous server sync-state
     * @return the server syncState that should be used in our next sync request.
     */
    public static synchronized long updateContacts(Context context,
                                                   String account, List<RawContact> rawContacts, long groupId,
                                                   long lastSyncMarker) {

        long currentSyncMarker = lastSyncMarker - 1 * 60 * 60 * 1000;
        final ContentResolver resolver = context.getContentResolver();
        final BatchOperation batchOperation = new BatchOperation(context,
                resolver);
        final List<RawContact> newUsers = new ArrayList<RawContact>();

        Log.d(TAG, "In SyncContacts");
        for (final RawContact rawContact : rawContacts) {
            // The server returns a syncState (x) value with each contact
            // record.
            // The syncState is sequential, so higher values represent more
            // recent
            // changes than lower values. We keep track of the highest value we
            // see, and consider that a "high water mark" for the changes we've
            // received from the server. That way, on our next sync, we can just
            // ask for changes that have occurred since that most-recent change.
            if (rawContact.getSyncState() > currentSyncMarker) {
                currentSyncMarker = rawContact.getSyncState();
            }

            // If the server returned a clientId for this user, then it's likely
            // that the user was added here, and was just pushed to the server
            // for the first time. In that case, we need to update the main
            // row for this contact so that the RawContacts.SOURCE_ID value
            // contains the correct serverId.
            final long rawContactId;
            final boolean updateServerId;
            if (rawContact.getRawContactId() > 0) {
                rawContactId = rawContact.getRawContactId();
                updateServerId = true;
            } else {
                long serverContactId = rawContact.getServerContactId();
                rawContactId = lookupRawContact(resolver, serverContactId);
                updateServerId = false;
            }

            if (rawContactId != 0) {
                if (!rawContact.isDeleted()) {
                    updateContact(context, resolver, rawContact,
                            updateServerId, true, true, true, rawContactId,
                            batchOperation);
                } else {
                    deleteContact(context, rawContactId, batchOperation);
                }
            } else {
                Log.d(TAG, "In addContact");
                if (!rawContact.isDeleted()) {
                    newUsers.add(rawContact);
                    addContact(context, account, rawContact, groupId, true,
                            batchOperation);
                }
            }
            // A sync adapter should batch operations on multiple contacts,
            // because it will make a dramatic performance difference.
            // (UI updates, etc)
            if (batchOperation.size() >= 10) {
                if (true) {
                    try {
                        batchOperation.execute();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

            }
        }

        if (true) {
            try {
                batchOperation.execute();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return currentSyncMarker;
    }

    /**
     * Return a list of the local contacts that have been marked as "dirty", and
     * need syncing to the ProjectForge server.
     *
     * @param context The context of Authenticator Activity
     * @param account The account that we're interested in syncing
     * @return a Map of Users that are considered "dirty". Key is the serverContactId
     */
    public static Map<Long, RawContact> getDirtyContacts(Context context,
                                                         Account account) {
        Log.i(TAG, "*** Looking for local dirty contacts");
        Map<Long, RawContact> dirtyContacts = new HashMap<Long, RawContact>();

        final ContentResolver resolver = context.getContentResolver();
        final Cursor c = resolver.query(DirtyQuery.CONTENT_URI,
                DirtyQuery.PROJECTION, DirtyQuery.SELECTION,
                new String[]{account.name}, null);
        try {
            while (c.moveToNext()) {
                final long rawContactId = c
                        .getLong(DirtyQuery.COLUMN_RAW_CONTACT_ID);
                final long serverContactId = c
                        .getLong(DirtyQuery.COLUMN_SERVER_ID);
                final boolean isDirty = "1".equals(c
                        .getString(DirtyQuery.COLUMN_DIRTY));
                final boolean isDeleted = "1".equals(c
                        .getString(DirtyQuery.COLUMN_DELETED));

                // The system actually keeps track of a change version number
                // for
                // each contact. It may be something you're interested in for
                // your
                // client-server sync protocol. We're not using it in this
                // example,
                // other than to log it.
                final long version = c.getLong(DirtyQuery.COLUMN_VERSION);

                Log.i(TAG, "Dirty Contact: " + Long.toString(rawContactId));
                Log.i(TAG, "Contact Version: " + Long.toString(version));

                if (isDeleted) {
                    Log.i(TAG, "Contact is marked for deletion");
                    RawContact rawContact = RawContact.createDeletedContact(
                            rawContactId, serverContactId);
                    dirtyContacts.put(serverContactId, rawContact);
                } else if (isDirty) {
                    RawContact rawContact = RawContact.createModifiedContact(
                            rawContactId, serverContactId);
                    Log.i(TAG, "Contact Name: " + rawContact.getBestName());
                    dirtyContacts.put(serverContactId, rawContact);
                }
            }

        } finally {
            if (c != null) {
                c.close();
            }
        }
        return dirtyContacts;
    }

    /**
     * Update the status messages for a list of users. This is typically called
     * for contacts we've just added to the system, since we can't monkey with
     * the contact's status until they have a profileId.
     *
     * @param context
     *            The context of Authenticator Activity
     * @param rawContacts
     *            The list of users we want to update
     */
    // public static void updateStatusMessages(Context context,
    // List<RawContact> rawContacts) {
    // final ContentResolver resolver = context.getContentResolver();
    // final BatchOperation batchOperation = new BatchOperation(context,
    // resolver);
    // for (RawContact rawContact : rawContacts) {
    // updateContactStatus(context, rawContact, batchOperation);
    // }
    // batchOperation.execute();
    // }

    /**
     * After we've finished up a sync operation, we want to clean up the
     * sync-state so that we're ready for the next time. This involves clearing
     * out the 'dirty' flag on the synced contacts - but we also have to finish
     * the DELETE operation on deleted contacts. When the user initially deletes
     * them on the client, they're marked for deletion - but they're not
     * actually deleted until we delete them again, and include the
     * ContactsContract.CALLER_IS_SYNCADAPTER parameter to tell the contacts
     * provider that we're really ready to let go of this contact.
     *
     * @param context       The context of Authenticator Activity
     * @param dirtyContacts The list of contacts that we're cleaning up
     */
    public static void clearSyncFlags(Context context,
                                      List<RawContact> dirtyContacts) {
        Log.i(TAG, "*** Clearing Sync-related Flags");
        final ContentResolver resolver = context.getContentResolver();
        final BatchOperation batchOperation = new BatchOperation(context,
                resolver);
        for (RawContact rawContact : dirtyContacts) {
            if (rawContact.isDeleted()) {
                Log.i(TAG,
                        "Deleting contact: "
                                + Long.toString(rawContact.getRawContactId()));
                deleteContact(context, rawContact.getRawContactId(),
                        batchOperation);
            } else if (rawContact.isDirty()) {
                Log.i(TAG,
                        "Clearing dirty flag for: " + rawContact.getBestName());
                clearDirtyFlag(context, rawContact.getRawContactId(),
                        batchOperation);
            }
        }
        batchOperation.execute();
    }

    /**
     * Adds a single contact to the platform contacts provider. This can be used
     * to respond to a new contact found as part of sync information returned
     * from the server, or because a user added a new contact.
     *
     * @param context        the Authenticator Activity context
     * @param accountName    the account the contact belongs to
     * @param rawContact     the sample SyncAdapter User object
     * @param groupId        the id of the sample group
     * @param inSync         is the add part of a client-server sync?
     * @param batchOperation allow us to batch together multiple operations into a single                       provider call
     */
    public static void addContact(Context context, String accountName,
                                  RawContact rawContact, long groupId, boolean inSync,
                                  BatchOperation batchOperation) {

        // Put the data in the contacts provider
        final ContactOperations contactOp = ContactOperations.createNewContact(
                context, rawContact.getServerContactId(), accountName, inSync,
                batchOperation);

        contactOp
                .addName(
                /* rawContact.getFullName(), */rawContact.getFirstName(),
                        rawContact.getLastName())

                .addEmail(rawContact.getWorkEmail(), Email.TYPE_WORK)
                //
                .addEmail(rawContact.getHomeEmail(), Email.TYPE_HOME)
                //

                .addFax(rawContact.getWorkFax(), Phone.TYPE_FAX_WORK)
                //

                .addPhone(rawContact.getHomeMobilePhone(), Phone.TYPE_MOBILE)
                //
                .addPhone(rawContact.getHomePhone(), Phone.TYPE_HOME)
                //
                .addPhone(rawContact.getWorkMobilePhone(),
                        Phone.TYPE_WORK_MOBILE)
                //
                .addPhone(rawContact.getWorkPhone(), Phone.TYPE_WORK)
                //
                .addOrganization(rawContact.getCompany(),
                        rawContact.getDivision(), rawContact.getPosition())
                //
                .addWebsite(rawContact.getWebsite())
                //
                .addAddr(rawContact.getAddr(), StructuredPostal.TYPE_WORK)
                .addAddr(rawContact.getPrivateAddr(),
                        StructuredPostal.TYPE_HOME)
                .addAddr(rawContact.getPostalAddr(),
                        StructuredPostal.TYPE_CUSTOM, "Postal")
                .addGroupMembership(groupId)
                .addAvatar(rawContact.getAvatar());

        // If we have a serverId, then go ahead and create our status profile.
        // Otherwise skip it - and we'll create it after we sync-up to the
        // server later on.
        if (rawContact.getServerContactId() > 0) {
            contactOp.addProfileAction(rawContact.getServerContactId());
        }
    }

    /**
     * Updates a single contact to the platform contacts provider. This method
     * can be used to update a contact from a sync operation or as a result of a
     * user editing a contact record.
     * <p>
     * This operation is actually relatively complex. We query the database to
     * find all the rows of info that already exist for this Contact. For rows
     * that exist (and thus we're modifying existing fields), we create an
     * update operation to change that field. But for fields we're adding, we
     * create "add" operations to create new rows for those fields.
     *
     * @param context        the Authenticator Activity context
     * @param resolver       the ContentResolver to use
     * @param rawContact     the sample SyncAdapter contact object
     * @param updateServerId the update server id
     * @param updateStatus   should we update this user's status
     * @param updateAvatar   should we update this user's avatar image
     * @param inSync         is the update part of a client-server sync?
     * @param rawContactId   the unique Id for this rawContact in contacts provider
     * @param batchOperation allow us to batch together multiple operations into a single                       provider call
     */
    public static void updateContact(Context context, ContentResolver resolver,
                                     RawContact rawContact, boolean updateServerId,
                                     boolean updateStatus, boolean updateAvatar, boolean inSync,
                                     long rawContactId, BatchOperation batchOperation) {

        boolean existingMobilePhone = false;
        boolean existingHomePhone = false;
        boolean existingWorkPhone = false;
        boolean existingWorkMobilePhone = false;
        boolean existingHomeEmail = false;
        boolean existingWorkEmail = false;

        boolean existingWorkFax = false;

        boolean existingOrganization = false;

        boolean existingWebsite = false;

        boolean existingNote = false;

        boolean existingAddr = false;

        boolean existingAddrPrivate = false;

        boolean existingAddrPostal = false;

        // boolean existingOrganizationDivision = false;

        // boolean existingOrganizationPosition = false;

        boolean existingAvatar = false;

        final Cursor c = resolver.query(DataQuery.CONTENT_URI,
                DataQuery.PROJECTION, DataQuery.SELECTION,
                new String[]{String.valueOf(rawContactId)}, null);
        final ContactOperations contactOp = ContactOperations
                .updateExistingContact(context, rawContactId, inSync,
                        batchOperation);
        try {
            // Iterate over the existing rows of data, and update each one
            // with the information we received from the server.
            while (c.moveToNext()) {
                final long id = c.getLong(DataQuery.COLUMN_ID);
                final String mimeType = c.getString(DataQuery.COLUMN_MIMETYPE);
                final Uri uri = ContentUris
                        .withAppendedId(Data.CONTENT_URI, id);
                if (mimeType.equals(StructuredName.CONTENT_ITEM_TYPE)) {
                    contactOp
                            .updateName(uri,
                                    c.getString(DataQuery.COLUMN_GIVEN_NAME),
                                    c.getString(DataQuery.COLUMN_FAMILY_NAME),
                                    rawContact.getFirstName(),
                                    rawContact.getLastName());
                } else if (mimeType.equals(Phone.CONTENT_ITEM_TYPE)) {

                    final int type = c.getInt(DataQuery.COLUMN_PHONE_TYPE);

                    if (type == Phone.TYPE_MOBILE) {
                        existingMobilePhone = true;
                        contactOp
                                .updatePhone(
                                        rawContact.getHomeMobilePhone(),
                                        c.getString(DataQuery.COLUMN_PHONE_NUMBER),
                                        uri);
                    } else if (type == Phone.TYPE_HOME) {
                        existingHomePhone = true;
                        contactOp
                                .updatePhone(
                                        rawContact.getHomePhone(),
                                        c.getString(DataQuery.COLUMN_PHONE_NUMBER),
                                        uri);
                    } else if (type == Phone.TYPE_WORK) {
                        existingWorkPhone = true;
                        contactOp
                                .updatePhone(
                                        rawContact.getWorkPhone(),
                                        c.getString(DataQuery.COLUMN_PHONE_NUMBER),
                                        uri);
                    } else if (type == Phone.TYPE_WORK_MOBILE) {
                        existingWorkMobilePhone = true;
                        contactOp
                                .updatePhone(
                                        rawContact.getWorkMobilePhone(),
                                        c.getString(DataQuery.COLUMN_PHONE_NUMBER),
                                        uri);

                    } else if (type == Phone.TYPE_FAX_WORK) {
                        existingWorkFax = true;
                        contactOp
                                .updatePhone(
                                        rawContact.getWorkFax(),
                                        c.getString(DataQuery.COLUMN_PHONE_NUMBER),
                                        uri);
                    }
                } else if (mimeType.equals(Email.CONTENT_ITEM_TYPE)) {
                    final int type = c.getInt(DataQuery.COLUMN_EMAIL_TYPE);

                    if (type == Email.TYPE_HOME) {
                        existingHomeEmail = true;
                        contactOp.updateEmail(rawContact.getHomeEmail(),
                                c.getString(DataQuery.COLUMN_EMAIL_ADDRESS),
                                uri);
                    } else if (type == Email.TYPE_WORK) {
                        existingWorkEmail = true;
                        contactOp.updateEmail(rawContact.getWorkEmail(),
                                c.getString(DataQuery.COLUMN_EMAIL_ADDRESS),
                                uri);
                    }
                } else if (mimeType.equals(Organization.CONTENT_ITEM_TYPE)) {

                    int type = c.getInt(DataQuery.COLUMN_ORGANIZATION_TYPE);

                    if (type == Organization.TYPE_WORK) {
                        existingOrganization = true;
                        contactOp
                                .updateOrganization(
                                        rawContact.getCompany(),
                                        rawContact.getDivision(),
                                        rawContact.getPosition(),
                                        c.getString(DataQuery.COLUMN_ORGANIZATION),
                                        c.getString(DataQuery.COLUMN_ORGANIZATION_DIVISION),
                                        c.getString(DataQuery.COLUMN_ORGANIZATION_POSITION),
                                        uri);

                    } else if (type == Organization.TYPE_CUSTOM) {
                        // nothing yet
                    } else if (type == Organization.TYPE_OTHER) {
                        // nothing yet
                    }
                } else if (mimeType.equals(Website.CONTENT_ITEM_TYPE)) {
                    existingWebsite = true;
                    contactOp.updateWebiste(rawContact.getWebsite(),
                            c.getString(DataQuery.COLUMN_WEBSITE), uri);
                } else if (mimeType.equals(Note.CONTENT_ITEM_TYPE)) {
                    existingNote = true;
                    contactOp.updateNote(rawContact.getNote(),
                            c.getString(DataQuery.COLUMN_NOTE), uri);
                } else if (mimeType.equals(StructuredPostal.CONTENT_ITEM_TYPE)) {
                    int type = c.getInt(DataQuery.COLUMN_ADDR_TYPE);

                    if (type == StructuredPostal.TYPE_HOME) {
                        existingAddrPrivate = true;
                        RawAddress addr = convert(c);
                        contactOp.updateAddr(rawContact.getPrivateAddr(), addr,
                                uri);
                    } else if (type == StructuredPostal.TYPE_WORK) {
                        existingAddr = true;
                        RawAddress addr = convert(c);
                        contactOp.updateAddr(rawContact.getAddr(), addr, uri);
                    } else if (type == StructuredPostal.TYPE_CUSTOM) {// TODO
                        // compare
                        // here
                        // Label
                        existingAddrPostal = true;
                        RawAddress addr = convert(c);
                        contactOp.updateAddr(rawContact.getPostalAddr(), addr,
                                uri);

                    } else if (mimeType.equals(Photo.CONTENT_ITEM_TYPE)) {
                        existingAvatar = true;
                        contactOp.updateAvatar(rawContact.getAvatar(), uri);
                    }
                }
            } // while
        } finally {
            c.close();
        }

        // Add the cell phone, if present and not updated above
        if (!existingMobilePhone) {
            contactOp.addPhone(rawContact.getHomeMobilePhone(),
                    Phone.TYPE_MOBILE);
        }
        // Add the home phone, if present and not updated above
        if (!existingHomePhone) {
            contactOp.addPhone(rawContact.getHomePhone(), Phone.TYPE_HOME);
        }

        // Add the work phone, if present and not updated above
        if (!existingWorkPhone) {
            contactOp.addPhone(rawContact.getWorkPhone(), Phone.TYPE_WORK);
        }

        // Add the work phone, if present and not updated above
        if (!existingWorkMobilePhone) {
            contactOp.addPhone(rawContact.getWorkMobilePhone(),
                    Phone.TYPE_WORK_MOBILE);
        }

        // Add work fax
        if (!existingWorkFax) {
            contactOp.addPhone(rawContact.getWorkFax(), Phone.TYPE_FAX_WORK);
        }

        // Add the email address, if present and not updated above
        if (!existingWorkEmail) {
            contactOp.addEmail(rawContact.getHomeEmail(), Email.TYPE_HOME);
        }

        if (!existingHomeEmail) {
            contactOp.addEmail(rawContact.getWorkEmail(), Email.TYPE_WORK);
        }

        if (!existingOrganization) {
            contactOp.addOrganization(rawContact.getCompany(),
                    rawContact.getDivision(), rawContact.getPosition());
        }

        if (!existingWebsite) {
            contactOp.addWebsite(rawContact.getWebsite());
        }

        if (!existingNote) {
            contactOp.addNote(rawContact.getNote());
        }

        if (!existingAddr) {
            contactOp.addAddr(rawContact.getAddr(), StructuredPostal.TYPE_WORK);
        }

        if (!existingAddrPrivate) {
            contactOp.addAddr(rawContact.getPrivateAddr(),
                    StructuredPostal.TYPE_HOME);
        }

        if (!existingAddrPostal) {
            contactOp.addAddr(rawContact.getPostalAddr(),
                    StructuredPostal.TYPE_CUSTOM, "Postal");// TODO Postal in
            // strings.xml
        }

        // Add the avatar if we didn't update the existing avatar
        if (!existingAvatar) {
            contactOp.addAvatar(rawContact.getAvatar());
        }

        // If we need to update the serverId of the contact record, take
        // care of that. This will happen if the contact is created on the
        // client, and then synced to the server. When we get the updated
        // record back from the server, we can set the SOURCE_ID property
        // on the contact, so we can (in the future) lookup contacts by
        // the serverId.
        if (updateServerId) {
            Uri uri = ContentUris.withAppendedId(RawContacts.CONTENT_URI,
                    rawContactId);
            contactOp.updateServerId(rawContact.getServerContactId(), uri);
        }

        // If we don't have a status profile, then create one. This could
        // happen for contacts that were created on the client - we don't
        // create the status profile until after the first sync...
        final long serverId = rawContact.getServerContactId();
        final long profileId = lookupProfile(resolver, serverId);
        if (profileId <= 0) {
            contactOp.addProfileAction(serverId);
        }
    }

    private static RawAddress convert(Cursor c) {

        RawAddress address = new RawAddress(
                c.getString(DataQuery.COLUMN_ADDR_STREET_HNR),
                c.getString(DataQuery.COLUMN_ADDR_ZIPCODE),
                c.getString(DataQuery.COLUMN_ADDR_CITY),
                c.getString(DataQuery.COLUMN_ADDR_STATE),
                c.getString(DataQuery.COLUMN_ADDR_COUNTRY));

        return address;
    }

    /**
     * When we first add a sync adapter to the system, the contacts from that
     * sync adapter will be hidden unless they're merged/grouped with an
     * existing contact. But typically we want to actually show those contacts,
     * so we need to mess with the Settings table to get them to show up.
     *
     * @param context the Authenticator Activity context
     * @param account the Account who's visibility we're changing
     * @param visible true if we want the contacts visible, false for hidden
     */
    public static void setAccountContactsVisibility(Context context,
                                                    Account account, boolean visible) {
        ContentValues values = new ContentValues();
        values.put(RawContacts.ACCOUNT_NAME, account.name);
        values.put(RawContacts.ACCOUNT_TYPE, Constants.ACCOUNT_TYPE);
        values.put(Settings.UNGROUPED_VISIBLE, visible ? 1 : 0);

        context.getContentResolver().insert(Settings.CONTENT_URI, values);
    }

    /**
     * Return a User object with data extracted from a contact stored in the
     * local contacts database.
     * <p>
     * Because a contact is actually stored over several rows in the database,
     * our query will return those multiple rows of information. We then iterate
     * over the rows and build the User structure from what we find.
     *
     * @param context      the Authenticator Activity context
     * @param rawContactId the unique ID for the local contact
     * @return a User object containing info on that contact
     */
    private static RawContact getRawContact(Context context, long rawContactId) {
        String firstName = null;
        String lastName = null;
        String fullName = null;
        String cellPhone = null;
        String homePhone = null;
        String workPhone = null;
        String email = null;
        long serverId = -1;

        final ContentResolver resolver = context.getContentResolver();
        final Cursor c = resolver.query(DataQuery.CONTENT_URI,
                DataQuery.PROJECTION, DataQuery.SELECTION,
                new String[]{String.valueOf(rawContactId)}, null);
        try {
            while (c.moveToNext()) {
                final long id = c.getLong(DataQuery.COLUMN_ID);
                final String mimeType = c.getString(DataQuery.COLUMN_MIMETYPE);
                final long tempServerId = c.getLong(DataQuery.COLUMN_SERVER_ID);
                if (tempServerId > 0) {
                    serverId = tempServerId;
                }
                final Uri uri = ContentUris
                        .withAppendedId(Data.CONTENT_URI, id);
                if (mimeType.equals(StructuredName.CONTENT_ITEM_TYPE)) {
                    lastName = c.getString(DataQuery.COLUMN_FAMILY_NAME);
                    firstName = c.getString(DataQuery.COLUMN_GIVEN_NAME);
                    fullName = c.getString(DataQuery.COLUMN_FULL_NAME);
                } else if (mimeType.equals(Phone.CONTENT_ITEM_TYPE)) {
                    final int type = c.getInt(DataQuery.COLUMN_PHONE_TYPE);
                    if (type == Phone.TYPE_MOBILE) {
                        cellPhone = c.getString(DataQuery.COLUMN_PHONE_NUMBER);
                    } else if (type == Phone.TYPE_HOME) {
                        homePhone = c.getString(DataQuery.COLUMN_PHONE_NUMBER);
                    } else if (type == Phone.TYPE_WORK) {
                        workPhone = c.getString(DataQuery.COLUMN_PHONE_NUMBER);
                    }
                } else if (mimeType.equals(Email.CONTENT_ITEM_TYPE)) {
                    email = c.getString(DataQuery.COLUMN_EMAIL_ADDRESS);
                }
            } // while
        } finally {
            c.close();
        }

        // Now that we've extracted all the information we care about,
        // create the actual User object.
        // RawContact rawContact = RawContact.create(fullName, firstName,
        // lastName, cellPhone,
        // workPhone, homePhone, email, null, false, rawContactId, serverId);

        return null;
    }

    /**
     * Update the status message associated with the specified user. The status
     * message would be something that is likely to be used by IM or social
     * networking sync providers, and less by a straightforward contact
     * provider. But it's a useful demo to see how it's done.
     *
     * @param context
     *            the Authenticator Activity context
     * @param rawContact
     *            the contact who's status we should update
     * @param batchOperation
     *            allow us to batch together multiple operations
     */
    // private static void updateContactStatus(Context context,
    // RawContact rawContact, BatchOperation batchOperation) {
    // final ContentValues values = new ContentValues();
    // final ContentResolver resolver = context.getContentResolver();
    //
    // final long userId = rawContact.getServerContactId();
    // // final String username = rawContact.getUserName();
    // final String status = rawContact.getStatus();
    //
    // // Look up the user's sample SyncAdapter data row
    // final long profileId = lookupProfile(resolver, userId);
    //
    // // Insert the activity into the stream
    // if (profileId > 0) {
    // values.put(StatusUpdates.DATA_ID, profileId);
    // values.put(StatusUpdates.STATUS, status);
    // values.put(StatusUpdates.PROTOCOL, Im.PROTOCOL_CUSTOM);
    // values.put(StatusUpdates.CUSTOM_PROTOCOL, CUSTOM_IM_PROTOCOL);
    // // values.put(StatusUpdates.IM_ACCOUNT, username);
    // values.put(StatusUpdates.IM_HANDLE, userId);
    // values.put(StatusUpdates.STATUS_RES_PACKAGE,
    // context.getPackageName());
    // values.put(StatusUpdates.STATUS_ICON, R.drawable.icon);
    // values.put(StatusUpdates.STATUS_LABEL, R.string.label);
    // batchOperation.add(ContactOperations
    // .newInsertCpo(StatusUpdates.CONTENT_URI, false, true)
    // .withValues(values).build());
    // }
    // }

    /**
     * Clear the local system 'dirty' flag for a contact.
     *
     * @param context        the Authenticator Activity context
     * @param rawContactId   the id of the contact update
     * @param batchOperation allow us to batch together multiple operations
     */
    private static void clearDirtyFlag(Context context, long rawContactId,
                                       BatchOperation batchOperation) {
        final ContactOperations contactOp = ContactOperations
                .updateExistingContact(context, rawContactId, true,
                        batchOperation);

        final Uri uri = ContentUris.withAppendedId(RawContacts.CONTENT_URI,
                rawContactId);
        contactOp.updateDirtyFlag(false, uri);
    }

    /**
     * Deletes a contact from the platform contacts provider. This method is
     * used both for contacts that were deleted locally and then that deletion
     * was synced to the server, and for contacts that were deleted on the
     * server and the deletion was synced to the client.
     *
     * @param context      the Authenticator Activity context
     * @param rawContactId the unique Id for this rawContact in contacts provider
     */
    private static void deleteContact(Context context, long rawContactId,
                                      BatchOperation batchOperation) {

        batchOperation.add(ContactOperations.newDeleteCpo(
                ContentUris.withAppendedId(RawContacts.CONTENT_URI,
                        rawContactId), true, true).build());
    }

    /**
     * Returns the RawContact id for a sample SyncAdapter contact, or 0 if the
     * sample SyncAdapter user isn't found.
     *
     * @param resolver        the content resolver to use
     * @param serverContactId the sample SyncAdapter user ID to lookup
     * @return the RawContact id, or 0 if not found
     */
    private static long lookupRawContact(ContentResolver resolver,
                                         long serverContactId) {

        long rawContactId = 0;
        final Cursor c = resolver.query(UserIdQuery.CONTENT_URI,
                UserIdQuery.PROJECTION, UserIdQuery.SELECTION,
                new String[]{String.valueOf(serverContactId)}, null);
        try {
            if ((c != null) && c.moveToFirst()) {
                rawContactId = c.getLong(UserIdQuery.COLUMN_RAW_CONTACT_ID);
            }
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return rawContactId;
    }

    /**
     * Returns the Data id for a sample SyncAdapter contact's profile row, or 0
     * if the sample SyncAdapter user isn't found.
     *
     * @param resolver a content resolver
     * @param userId   the sample SyncAdapter user ID to lookup
     * @return the profile Data row id, or 0 if not found
     */
    private static long lookupProfile(ContentResolver resolver, long userId) {

        long profileId = 0;
        final Cursor c = resolver.query(Data.CONTENT_URI,
                ProfileQuery.PROJECTION, ProfileQuery.SELECTION,
                new String[]{String.valueOf(userId)}, null);
        try {
            if ((c != null) && c.moveToFirst()) {
                profileId = c.getLong(ProfileQuery.COLUMN_ID);
            }
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return profileId;
    }

    /**
     * The type Editor query.
     */
    final public static class EditorQuery {

        private EditorQuery() {
        }

        /**
         * The constant PROJECTION.
         */
        public static final String[] PROJECTION = new String[]{
                RawContacts.ACCOUNT_NAME,//
                Data._ID,//
                RawContacts.Entity.DATA_ID,//
                Data.MIMETYPE,//
                Data.DATA1,//
                Data.DATA2,//
                Data.DATA3,//
                Data.DATA15,//
                Data.SYNC1 //
        };

        /**
         * The constant COLUMN_ACCOUNT_NAME.
         */
        public static final int COLUMN_ACCOUNT_NAME = 0;
        /**
         * The constant COLUMN_RAW_CONTACT_ID.
         */
        public static final int COLUMN_RAW_CONTACT_ID = 1;
        /**
         * The constant COLUMN_DATA_ID.
         */
        public static final int COLUMN_DATA_ID = 2;
        /**
         * The constant COLUMN_MIMETYPE.
         */
        public static final int COLUMN_MIMETYPE = 3;
        /**
         * The constant COLUMN_DATA1.
         */
        public static final int COLUMN_DATA1 = 4;
        /**
         * The constant COLUMN_DATA2.
         */
        public static final int COLUMN_DATA2 = 5;
        /**
         * The constant COLUMN_DATA3.
         */
        public static final int COLUMN_DATA3 = 6;
        /**
         * The constant COLUMN_DATA15.
         */
        public static final int COLUMN_DATA15 = 7;
        /**
         * The constant COLUMN_SYNC1.
         */
        public static final int COLUMN_SYNC1 = 8;

        /**
         * The constant COLUMN_PHONE_NUMBER.
         */
        public static final int COLUMN_PHONE_NUMBER = COLUMN_DATA1;
        /**
         * The constant COLUMN_PHONE_TYPE.
         */
        public static final int COLUMN_PHONE_TYPE = COLUMN_DATA2;
        /**
         * The constant COLUMN_EMAIL_ADDRESS.
         */
        public static final int COLUMN_EMAIL_ADDRESS = COLUMN_DATA1;
        /**
         * The constant COLUMN_EMAIL_TYPE.
         */
        public static final int COLUMN_EMAIL_TYPE = COLUMN_DATA2;
        /**
         * The constant COLUMN_FULL_NAME.
         */
        public static final int COLUMN_FULL_NAME = COLUMN_DATA1;
        /**
         * The constant COLUMN_GIVEN_NAME.
         */
        public static final int COLUMN_GIVEN_NAME = COLUMN_DATA2;
        /**
         * The constant COLUMN_FAMILY_NAME.
         */
        public static final int COLUMN_FAMILY_NAME = COLUMN_DATA3;
        /**
         * The constant COLUMN_AVATAR_IMAGE.
         */
        public static final int COLUMN_AVATAR_IMAGE = COLUMN_DATA15;
        /**
         * The constant COLUMN_SYNC_DIRTY.
         */
        public static final int COLUMN_SYNC_DIRTY = COLUMN_SYNC1;

        /**
         * The constant SELECTION.
         */
        public static final String SELECTION = Data.RAW_CONTACT_ID + "=?";
    }

    /**
     * Constants for a query to find a contact given a sample SyncAdapter user
     * ID.
     */
    final private static class ProfileQuery {

        private ProfileQuery() {
        }

        /**
         * The constant PROJECTION.
         */
        public final static String[] PROJECTION = new String[]{Data._ID};

        /**
         * The constant COLUMN_ID.
         */
        public final static int COLUMN_ID = 0;

        /**
         * The constant SELECTION.
         */
        public static final String SELECTION = Data.MIMETYPE + "='"
                + ProjectforgeSyncAdapterColumns.MIME_PROFILE + "' AND "
                + ProjectforgeSyncAdapterColumns.DATA_PID + "=?";
    }

    /**
     * Constants for a query to find a contact given a sample SyncAdapter user
     * ID.
     */
    final private static class UserIdQuery {

        private UserIdQuery() {
        }

        /**
         * The constant PROJECTION.
         */
        public final static String[] PROJECTION = new String[]{
                RawContacts._ID, RawContacts.CONTACT_ID};

        /**
         * The constant COLUMN_RAW_CONTACT_ID.
         */
        public final static int COLUMN_RAW_CONTACT_ID = 0;
        /**
         * The constant COLUMN_LINKED_CONTACT_ID.
         */
        public final static int COLUMN_LINKED_CONTACT_ID = 1;

        /**
         * The constant CONTENT_URI.
         */
        public final static Uri CONTENT_URI = RawContacts.CONTENT_URI;

        /**
         * The constant SELECTION.
         */
        public static final String SELECTION = RawContacts.ACCOUNT_TYPE + "='"
                + Constants.ACCOUNT_TYPE + "' AND " + RawContacts.SOURCE_ID
                + "=?";
    }

    /**
     * Constants for a query to find ProjectForgeSyncAdapter contacts that are
     * in need of syncing to the server. This should cover new, edited, and
     * deleted contacts.
     */
    final private static class DirtyQuery {

        private DirtyQuery() {
        }

        /**
         * The constant PROJECTION.
         */
        public final static String[] PROJECTION = new String[]{
                RawContacts._ID, RawContacts.SOURCE_ID, RawContacts.DIRTY,
                RawContacts.DELETED, RawContacts.VERSION};

        /**
         * The constant COLUMN_RAW_CONTACT_ID.
         */
        public final static int COLUMN_RAW_CONTACT_ID = 0;
        /**
         * The constant COLUMN_SERVER_ID.
         */
        public final static int COLUMN_SERVER_ID = 1;
        /**
         * The constant COLUMN_DIRTY.
         */
        public final static int COLUMN_DIRTY = 2;
        /**
         * The constant COLUMN_DELETED.
         */
        public final static int COLUMN_DELETED = 3;
        /**
         * The constant COLUMN_VERSION.
         */
        public final static int COLUMN_VERSION = 4;

        /**
         * The constant CONTENT_URI.
         */
        public static final Uri CONTENT_URI = RawContacts.CONTENT_URI
                .buildUpon()
                .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER,
                        "true").build();

        /**
         * The constant SELECTION.
         */
        public static final String SELECTION = RawContacts.DIRTY + "=1 AND "
                + RawContacts.ACCOUNT_TYPE + "='" + Constants.ACCOUNT_TYPE
                + "' AND " + RawContacts.ACCOUNT_NAME + "=?";
    }

    /**
     * Constants for a query to get contact data for a given rawContactId
     */
    final private static class DataQuery {

        private DataQuery() {
        }

        /**
         * The constant PROJECTION.
         */
        public static final String[] PROJECTION = new String[]{Data._ID,//
                RawContacts.SOURCE_ID,//
                Data.MIMETYPE,//
                Data.DATA1,//
                Data.DATA2,//
                Data.DATA3,//
                Data.DATA15,//
                Data.SYNC1, //
                Data.DATA5,//
                Data.DATA4,//
                Data.DATA9,//
                Data.DATA7,//
                Data.DATA8,//
                Data.DATA10,//

        };

        /**
         * The constant COLUMN_ID.
         */
        public static final int COLUMN_ID = 0;
        /**
         * The constant COLUMN_SERVER_ID.
         */
        public static final int COLUMN_SERVER_ID = 1;
        /**
         * The constant COLUMN_MIMETYPE.
         */
        public static final int COLUMN_MIMETYPE = 2;
        /**
         * The constant COLUMN_DATA1.
         */
        public static final int COLUMN_DATA1 = 3;
        /**
         * The constant COLUMN_DATA2.
         */
        public static final int COLUMN_DATA2 = 4;
        /**
         * The constant COLUMN_DATA3.
         */
        public static final int COLUMN_DATA3 = 5;
        /**
         * The constant COLUMN_DATA15.
         */
        public static final int COLUMN_DATA15 = 6;
        /**
         * The constant COLUMN_SYNC1.
         */
        public static final int COLUMN_SYNC1 = 7;
        /**
         * The constant COLUMN_DATA5.
         */
        public static final int COLUMN_DATA5 = 8;
        /**
         * The constant COLUMN_DATA4.
         */
        public static final int COLUMN_DATA4 = 9;
        /**
         * The constant COLUMN_DATA9.
         */
        public static final int COLUMN_DATA9 = 10;
        /**
         * The constant COLUMN_DATA7.
         */
        public static final int COLUMN_DATA7 = 11;
        /**
         * The constant COLUMN_DATA8.
         */
        public static final int COLUMN_DATA8 = 12;
        /**
         * The constant COLUMN_DATA10.
         */
        public static final int COLUMN_DATA10 = 13;

        /**
         * The constant CONTENT_URI.
         */
        public static final Uri CONTENT_URI = Data.CONTENT_URI;

        /**
         * The constant COLUMN_PHONE_NUMBER.
         */
        public static final int COLUMN_PHONE_NUMBER = COLUMN_DATA1;
        /**
         * The constant COLUMN_PHONE_TYPE.
         */
        public static final int COLUMN_PHONE_TYPE = COLUMN_DATA2;

        /**
         * The constant COLUMN_EMAIL_ADDRESS.
         */
        public static final int COLUMN_EMAIL_ADDRESS = COLUMN_DATA1;
        /**
         * The constant COLUMN_EMAIL_TYPE.
         */
        public static final int COLUMN_EMAIL_TYPE = COLUMN_DATA2;

        /**
         * The constant COLUMN_ORGANIZATION_TYPE.
         */
        public static final int COLUMN_ORGANIZATION_TYPE = COLUMN_DATA2;
        /**
         * The constant COLUMN_ORGANIZATION.
         */
        public static final int COLUMN_ORGANIZATION = COLUMN_DATA1;// 1
        /**
         * The constant COLUMN_ORGANIZATION_DIVISION.
         */
        public static final int COLUMN_ORGANIZATION_DIVISION = COLUMN_DATA5; // 5
        /**
         * The constant COLUMN_ORGANIZATION_POSITION.
         */
        public static final int COLUMN_ORGANIZATION_POSITION = COLUMN_DATA4;// 4

        /**
         * The constant COLUMN_WEBSITE.
         */
        public static final int COLUMN_WEBSITE = COLUMN_DATA1;

        /**
         * The constant COLUMN_NOTE.
         */
        public static final int COLUMN_NOTE = COLUMN_DATA1;

        /**
         * The constant COLUMN_FULL_NAME.
         */
        public static final int COLUMN_FULL_NAME = COLUMN_DATA1;
        /**
         * The constant COLUMN_GIVEN_NAME.
         */
        public static final int COLUMN_GIVEN_NAME = COLUMN_DATA2;
        /**
         * The constant COLUMN_FAMILY_NAME.
         */
        public static final int COLUMN_FAMILY_NAME = COLUMN_DATA3;
        /**
         * The constant COLUMN_AVATAR_IMAGE.
         */
        public static final int COLUMN_AVATAR_IMAGE = COLUMN_DATA15;
        /**
         * The constant COLUMN_SYNC_DIRTY.
         */
        public static final int COLUMN_SYNC_DIRTY = COLUMN_SYNC1;

        /**
         * The constant COLUMN_ADDR_TYPE.
         */
        public static final int COLUMN_ADDR_TYPE = COLUMN_DATA2;
        /**
         * The constant COLUMN_ADDR_STREET_HNR.
         */
        public static final int COLUMN_ADDR_STREET_HNR = COLUMN_DATA4;
        /**
         * The constant COLUMN_ADDR_ZIPCODE.
         */
        public static final int COLUMN_ADDR_ZIPCODE = COLUMN_DATA9;
        /**
         * The constant COLUMN_ADDR_CITY.
         */
        public static final int COLUMN_ADDR_CITY = COLUMN_DATA7;

        /**
         * The constant COLUMN_ADDR_STATE.
         */
        public static final int COLUMN_ADDR_STATE = COLUMN_DATA8;

        /**
         * The constant COLUMN_ADDR_COUNTRY.
         */
        public static final int COLUMN_ADDR_COUNTRY = COLUMN_DATA10;

        /**
         * The constant SELECTION.
         */
        public static final String SELECTION = Data.RAW_CONTACT_ID + "=?";
    }

    /**
     * Constants for a query to read basic contact columns
     */
    final public static class ContactQuery {
        private ContactQuery() {
        }

        /**
         * The constant PROJECTION.
         */
        public static final String[] PROJECTION = new String[]{Contacts._ID,
                Contacts.DISPLAY_NAME};

        /**
         * The constant COLUMN_ID.
         */
        public static final int COLUMN_ID = 0;
        /**
         * The constant COLUMN_DISPLAY_NAME.
         */
        public static final int COLUMN_DISPLAY_NAME = 1;
    }
}
