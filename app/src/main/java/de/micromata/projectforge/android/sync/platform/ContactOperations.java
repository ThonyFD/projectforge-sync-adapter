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

import android.content.ContentProviderOperation;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.CommonDataKinds.GroupMembership;
import android.provider.ContactsContract.CommonDataKinds.Note;
import android.provider.ContactsContract.CommonDataKinds.Organization;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.Photo;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal;
import android.provider.ContactsContract.CommonDataKinds.Website;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.RawContacts;
import android.text.TextUtils;

import de.micromata.projectforge.android.sync.Constants;
import de.micromata.projectforge.android.sync.R;
import de.micromata.projectforge.android.sync.client.RawAddress;


/**
 * Helper class for storing data in the platform content providers.
 */
public class ContactOperations
{
  private final ContentValues mValues;

  private final BatchOperation mBatchOperation;

  private final Context mContext;

  private boolean mIsSyncOperation;

  private long mRawContactId;

  private int mBackReference;

  private boolean mIsNewContact;

  /**
   * Since we're sending a lot of contact provider operations in a single
   * batched operation, we want to make sure that we "yield" periodically so
   * that the Contact Provider can write changes to the DB, and can open a new
   * transaction. This prevents ANR (application not responding) errors. The
   * recommended time to specify that a yield is permitted is with the first
   * operation on a particular contact. So if we're updating multiple fields
   * for a single contact, we make sure that we call withYieldAllowed(true) on
   * the first field that we update. We use mIsYieldAllowed to keep track of
   * what value we should pass to withYieldAllowed().
   */
  private boolean mIsYieldAllowed;

  /**
   * Returns an instance of ContactOperations instance for adding new contact
   * to the platform contacts provider.
   *
   * @param context         the Authenticator Activity context
   * @param userId          the userId of the sample SyncAdapter user object
   * @param accountName     the username for the SyncAdapter account
   * @param isSyncOperation are we executing this as part of a sync operation?
   * @param batchOperation  the batch operation
   * @return instance of ContactOperations
   */
  public static ContactOperations createNewContact(Context context,
      long userId, String accountName, boolean isSyncOperation,
      BatchOperation batchOperation)
  {
    return new ContactOperations(context, userId, accountName,
        isSyncOperation, batchOperation);
  }

  /**
   * Returns an instance of ContactOperations for updating existing contact in
   * the platform contacts provider.
   *
   * @param context         the Authenticator Activity context
   * @param rawContactId    the unique Id of the existing rawContact
   * @param isSyncOperation are we executing this as part of a sync operation?
   * @param batchOperation  the batch operation
   * @return instance of ContactOperations
   */
  public static ContactOperations updateExistingContact(Context context,
      long rawContactId, boolean isSyncOperation,
      BatchOperation batchOperation)
  {
    return new ContactOperations(context, rawContactId, isSyncOperation,
        batchOperation);
  }

  /**
   * Instantiates a new Contact operations.
   *
   * @param context         the context
   * @param isSyncOperation the is sync operation
   * @param batchOperation  the batch operation
   */
  public ContactOperations(Context context, boolean isSyncOperation,
      BatchOperation batchOperation)
  {
    mValues = new ContentValues();
    mIsYieldAllowed = true;
    mIsSyncOperation = isSyncOperation;
    mContext = context;
    mBatchOperation = batchOperation;
  }

  /**
   * Instantiates a new Contact operations.
   *
   * @param context         the context
   * @param userId          the user id
   * @param accountName     the account name
   * @param isSyncOperation the is sync operation
   * @param batchOperation  the batch operation
   */
  public ContactOperations(Context context, long userId, String accountName,
      boolean isSyncOperation, BatchOperation batchOperation)
  {
    this(context, isSyncOperation, batchOperation);
    mBackReference = mBatchOperation.size();
    mIsNewContact = true;
    mValues.put(RawContacts.SOURCE_ID, userId);
    mValues.put(RawContacts.ACCOUNT_TYPE, Constants.ACCOUNT_TYPE);
    mValues.put(RawContacts.ACCOUNT_NAME, accountName);
    ContentProviderOperation.Builder builder = newInsertCpo(
        RawContacts.CONTENT_URI, mIsSyncOperation, true).withValues(
        mValues);
    mBatchOperation.add(builder.build());
  }

  /**
   * Instantiates a new Contact operations.
   *
   * @param context         the context
   * @param rawContactId    the raw contact id
   * @param isSyncOperation the is sync operation
   * @param batchOperation  the batch operation
   */
  public ContactOperations(Context context, long rawContactId,
      boolean isSyncOperation, BatchOperation batchOperation)
  {
    this(context, isSyncOperation, batchOperation);
    mIsNewContact = false;
    mRawContactId = rawContactId;
  }

  /**
   * Adds a contact name. We can take either a full name ("Bob Smith") or
   * separated first-name and last-name ("Bob" and "Smith").
   *
   * @param firstName The first name of the contact - can be null if fullName is specified.
   * @param lastName  The last name of the contact - can be null if fullName is specified.
   * @return instance of ContactOperations
   */
  public ContactOperations addName(String firstName, String lastName)
  {
    mValues.clear();

    // if (!TextUtils.isEmpty(fullName)) {
    // mValues.put(StructuredName.DISPLAY_NAME, fullName);
    // mValues.put(StructuredName.MIMETYPE,
    // StructuredName.CONTENT_ITEM_TYPE);
    // } else {
    if (!TextUtils.isEmpty(firstName)) {
      mValues.put(StructuredName.GIVEN_NAME, firstName);
      mValues.put(StructuredName.MIMETYPE,
          StructuredName.CONTENT_ITEM_TYPE);
    }
    if (!TextUtils.isEmpty(lastName)) {
      mValues.put(StructuredName.FAMILY_NAME, lastName);
      mValues.put(StructuredName.MIMETYPE,
          StructuredName.CONTENT_ITEM_TYPE);
    }
    // }
    if (mValues.size() > 0) {
      addInsertOp();
    }
    return this;
  }

  /**
   * Adds an email
   *
   * @param email     the email
   * @param emailType the email type
   * @return instance of ContactOperations
   */
  public ContactOperations addEmail(String email, int emailType)
  {
    mValues.clear();
    if (!TextUtils.isEmpty(email)) {
      mValues.put(Email.DATA, email);
      mValues.put(Email.TYPE, emailType);
      mValues.put(Email.MIMETYPE, Email.CONTENT_ITEM_TYPE);
      addInsertOp();
    }
    return this;
  }

  /**
   * Adds an email
   *
   * @param fax     the fax
   * @param faxType the fax type
   * @return instance of ContactOperations
   */
  public ContactOperations addFax(String fax, int faxType)
  {
    mValues.clear();
    if (!TextUtils.isEmpty(fax)) {
      mValues.put(Phone.DATA, fax);
      mValues.put(Phone.TYPE, faxType);
      mValues.put(Phone.MIMETYPE, Phone.CONTENT_ITEM_TYPE);
      addInsertOp();
    }
    return this;
  }

  /**
   * Adds a website
   *
   * @param website the website
   * @return instance of ContactOperations
   */
  public ContactOperations addWebsite(String website)
  {
    mValues.clear();
    if (!TextUtils.isEmpty(website)) {
      mValues.put(Website.URL, website);
      mValues.put(Website.MIMETYPE, Website.CONTENT_ITEM_TYPE);
      addInsertOp();
    }
    return this;
  }

  /**
   * Adds a website
   *
   * @param note the note
   * @return instance of ContactOperations
   */
  public ContactOperations addNote(String note)
  {
    mValues.clear();
    if (!TextUtils.isEmpty(note)) {
      mValues.put(Note.NOTE, note);
      mValues.put(Note.MIMETYPE, Note.CONTENT_ITEM_TYPE);
      addInsertOp();
    }
    return this;
  }

  /**
   * Add addr contact operations.
   *
   * @param addr     the addr
   * @param addrType the addr type
   * @return the contact operations
   */
  public ContactOperations addAddr(RawAddress addr, int addrType)
  {
    return addAddr(addr, addrType, null);
  }

  /**
   * Add addr contact operations.
   *
   * @param addr     the addr
   * @param addrType the addr type
   * @param label    the label
   * @return the contact operations
   */
  public ContactOperations addAddr(RawAddress addr, int addrType, String label)
  {
    mValues.clear();
    if (addr != null && !addr.isEmpty()) {
      mValues.put(StructuredPostal.STREET, addr.getAddressText());
      mValues.put(StructuredPostal.CITY, addr.getCity());
      mValues.put(StructuredPostal.COUNTRY, addr.getCountry());
      mValues.put(StructuredPostal.POSTCODE, addr.getZipCode());
      mValues.put(StructuredPostal.REGION, addr.getState());
      mValues.put(StructuredPostal.TYPE, addrType);
      mValues.put(StructuredPostal.MIMETYPE,
          StructuredPostal.CONTENT_ITEM_TYPE);
      if (label != null) {
        mValues.put(StructuredPostal.LABEL, label);
      }
      addInsertOp();
    }
    return this;
  }

  /**
   * Adds a phone number
   *
   * @param phone     new phone number for the contact
   * @param phoneType the type: cell, home, etc.
   * @return instance of ContactOperations
   */
  public ContactOperations addPhone(String phone, int phoneType)
  {
    mValues.clear();
    if (!TextUtils.isEmpty(phone)) {
      mValues.put(Phone.NUMBER, phone);
      mValues.put(Phone.TYPE, phoneType);
      mValues.put(Phone.MIMETYPE, Phone.CONTENT_ITEM_TYPE);
      addInsertOp();
    }
    return this;
  }

  /**
   * Add organization contact operations.
   *
   * @param organization the organization
   * @param division     the division
   * @param position     the position
   * @return the contact operations
   */
  public ContactOperations addOrganization(String organization,
      String division, String position)
  {
    mValues.clear();
    if (!TextUtils.isEmpty(organization) || !TextUtils.isEmpty(division)
        || !TextUtils.isEmpty(position)) {
      mValues.put(Organization.TITLE, position);
      mValues.put(Organization.COMPANY, organization);
      mValues.put(Organization.DEPARTMENT, division);
      mValues.put(Organization.TYPE, Organization.TYPE_WORK);
      mValues.put(Organization.MIMETYPE, Organization.CONTENT_ITEM_TYPE);
      addInsertOp();
    }
    return this;
  }

  /**
   * Adds a group membership
   *
   * @param groupId the group id
   * @return instance of ContactOperations
   */
  public ContactOperations addGroupMembership(long groupId)
  {
    mValues.clear();
    mValues.put(GroupMembership.GROUP_ROW_ID, groupId);
    mValues.put(GroupMembership.MIMETYPE, GroupMembership.CONTENT_ITEM_TYPE);
    addInsertOp();
    return this;
  }

  /**
   * Add avatar contact operations.
   *
   * @param data the data
   * @return the contact operations
   */
  public ContactOperations addAvatar(byte[] data)
  {
    if (data != null) {
      mValues.clear();
      mValues.put(ContactsContract.Contacts.Photo.PHOTO, data);
      mValues.put(ContactsContract.Contacts.Photo.MIMETYPE, ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE);
      addInsertOp();
    }
    return this;
  }
  // }

  /**
   * Adds a profile action
   *
   * @param userId the userId of the sample SyncAdapter user object
   * @return instance of ContactOperations
   */
  public ContactOperations addProfileAction(long userId)
  {
    mValues.clear();
    if (userId != 0) {
      mValues.put(ProjectforgeSyncAdapterColumns.DATA_PID, userId);
      mValues.put(ProjectforgeSyncAdapterColumns.DATA_SUMMARY,
          mContext.getString(R.string.profile_action));
      mValues.put(ProjectforgeSyncAdapterColumns.DATA_DETAIL,
          mContext.getString(R.string.view_profile));
      mValues.put(Data.MIMETYPE,
          ProjectforgeSyncAdapterColumns.MIME_PROFILE);
      addInsertOp();
    }
    return this;
  }

  /**
   * Updates contact's serverId
   *
   * @param serverId the serverId for this contact
   * @param uri      Uri for the existing raw contact to be updated
   * @return instance of ContactOperations
   */
  public ContactOperations updateServerId(long serverId, Uri uri)
  {
    mValues.clear();
    mValues.put(RawContacts.SOURCE_ID, serverId);
    addUpdateOp(uri);
    return this;
  }

  /**
   * Updates contact's email
   *
   * @param email         email id of the sample SyncAdapter user
   * @param existingEmail the existing email
   * @param uri           Uri for the existing raw contact to be updated
   * @return instance of ContactOperations
   */
  public ContactOperations updateEmail(String email, String existingEmail,
      Uri uri)
  {
    mValues.clear();
    if (!TextUtils.equals(existingEmail, email)) {
      mValues.put(Email.DATA, email);
      addUpdateOp(uri);
    }
    return this;
  }

  /**
   * Update addr contact operations.
   *
   * @param addr         the addr
   * @param existingAddr the existing addr
   * @param uri          the uri
   * @return the contact operations
   */
  public ContactOperations updateAddr(RawAddress addr,
      RawAddress existingAddr, Uri uri)
  {
    mValues.clear();

    if (addr.isEmpty()) {
      addDeleteOp(uri);
      return this;
    }

    if (!TextUtils.equals(existingAddr.getAddressText(),
        addr.getAddressText())) {
      mValues.put(StructuredPostal.STREET, addr.getAddressText());
    }
    if (!TextUtils.equals(existingAddr.getCity(), addr.getCity())) {
      mValues.put(StructuredPostal.CITY, addr.getCity());
    }
    if (!TextUtils.equals(existingAddr.getCountry(), addr.getCountry())) {
      mValues.put(StructuredPostal.COUNTRY, addr.getCountry());
    }
    if (!TextUtils.equals(existingAddr.getZipCode(), addr.getZipCode())) {
      mValues.put(StructuredPostal.POSTCODE, addr.getZipCode());
    }
    if (!TextUtils.equals(existingAddr.getState(), addr.getState())) {
      mValues.put(StructuredPostal.REGION, addr.getState());
    }
    if (mValues.size() > 0) {
      addUpdateOp(uri);
    }
    return this;
  }

  /**
   * Updates contact's email
   *
   * @param organization         the organization
   * @param division             the division
   * @param position             the position
   * @param existingOrganization the existing organization
   * @param existingDivision     the existing division
   * @param existingPosition     the existing position
   * @param uri                  Uri for the existing raw contact to be updated
   * @return instance of ContactOperations
   */
  public ContactOperations updateOrganization(String organization,
      String division, String position, String existingOrganization,
      String existingDivision, String existingPosition, Uri uri)
  {
    mValues.clear();

    if (!TextUtils.equals(existingOrganization, organization)) {
      mValues.put(Organization.COMPANY, organization);
    }

    if (!TextUtils.equals(existingDivision, division)) {
      mValues.put(Organization.DEPARTMENT, division);
    }

    if (!TextUtils.equals(existingPosition, position)) {
      mValues.put(Organization.TITLE, position);
    }

    if (mValues.size() > 0) {
      addUpdateOp(uri);
    }
    return this;
  }

  /**
   * Updates contact's name. The caller can either provide first-name and
   * last-name fields or a full-name field.
   *
   * @param uri               Uri for the existing raw contact to be updated
   * @param existingFirstName the first name stored in provider
   * @param existingLastName  the last name stored in provider
   * @param firstName         the new first name to store
   * @param lastName          the new last name to store //TODO add prefix
   * @return instance of ContactOperations
   */
  public ContactOperations updateName(Uri uri, String existingFirstName,
      String existingLastName, String firstName, String lastName)
  {

    mValues.clear();
    if (!TextUtils.equals(existingFirstName, firstName)) {
      mValues.put(StructuredName.GIVEN_NAME, firstName);
    }
    if (!TextUtils.equals(existingLastName, lastName)) {
      mValues.put(StructuredName.FAMILY_NAME, lastName);
    }

    // StructuredName.PREFIX

    if (mValues.size() > 0) {
      addUpdateOp(uri);
    }
    return this;
  }

  /**
   * Update dirty flag contact operations.
   *
   * @param isDirty the is dirty
   * @param uri     the uri
   * @return the contact operations
   */
  public ContactOperations updateDirtyFlag(boolean isDirty, Uri uri)
  {
    int isDirtyValue = isDirty ? 1 : 0;
    mValues.clear();
    mValues.put(RawContacts.DIRTY, isDirtyValue);
    addUpdateOp(uri);
    return this;
  }

  /**
   * Updates contact's phone
   *
   * @param phone          new phone number for the contact
   * @param existingNumber phone number stored in contacts provider
   * @param uri            Uri for the existing raw contact to be updated
   * @return instance of ContactOperations
   */
  public ContactOperations updatePhone(String phone, String existingNumber,
      Uri uri)
  {
    if (!TextUtils.equals(phone, existingNumber)) {
      mValues.clear();
      mValues.put(Phone.NUMBER, phone);
      addUpdateOp(uri);
    }
    return this;
  }

  /**
   * Update avatar contact operations.
   *
   * @param avatar the avatar
   * @param uri    the uri
   * @return the contact operations
   */
  public ContactOperations updateAvatar(byte[] avatar, Uri uri)
  {
    if (avatar != null) {
      mValues.clear();
      mValues.put(Photo.PHOTO, avatar);
      mValues.put(Photo.MIMETYPE, Photo.CONTENT_ITEM_TYPE);
      addUpdateOp(uri);
    }
    return this;
  }

  /**
   * Update note.
   *
   * @param note         the note
   * @param existingNote the existing note
   * @param uri          the uri
   */
  public void updateNote(String note, String existingNote, Uri uri)
  {

    if (!TextUtils.equals(existingNote, note)) {
      mValues.clear();
      mValues.put(Note.NOTE, note);
      mValues.put(Note.MIMETYPE, Note.CONTENT_ITEM_TYPE);
      addUpdateOp(uri);
    }
  }

  /**
   * Update webiste contact operations.
   *
   * @param website        the website
   * @param existingWesite the existing wesite
   * @param uri            the uri
   * @return the contact operations
   */
  public ContactOperations updateWebiste(String website,
      String existingWesite, Uri uri)
  {
    if (!TextUtils.equals(website, existingWesite)) {
      mValues.clear();
      mValues.put(Website.URL, website);
      mValues.put(Website.MIMETYPE, Website.CONTENT_ITEM_TYPE);
      addUpdateOp(uri);
    }
    return this;
  }

  /**
   * Updates contact's profile action
   *
   * @param userId sample SyncAdapter user id
   * @param uri    Uri for the existing raw contact to be updated
   * @return instance of ContactOperations
   */
  public ContactOperations updateProfileAction(Integer userId, Uri uri)
  {
    mValues.clear();
    mValues.put(ProjectforgeSyncAdapterColumns.DATA_PID, userId);
    addUpdateOp(uri);
    return this;
  }

  /**
   * Adds an insert operation into the batch
   */
  private void addInsertOp()
  {

    if (!mIsNewContact) {
      mValues.put(Phone.RAW_CONTACT_ID, mRawContactId);
    }
    ContentProviderOperation.Builder builder = newInsertCpo(
        Data.CONTENT_URI, mIsSyncOperation, mIsYieldAllowed);
    builder.withValues(mValues);
    if (mIsNewContact) {
      builder.withValueBackReference(Data.RAW_CONTACT_ID, mBackReference);
    }
    mIsYieldAllowed = false;
    mBatchOperation.add(builder.build());
  }

  /**
   * Adds an update operation into the batch
   */
  private void addUpdateOp(Uri uri)
  {
    ContentProviderOperation.Builder builder = newUpdateCpo(uri,
        mIsSyncOperation, mIsYieldAllowed).withValues(mValues);
    mIsYieldAllowed = false;
    mBatchOperation.add(builder.build());
  }

  private void addDeleteOp(Uri uri)
  {
    ContentProviderOperation.Builder builder = newDeleteCpo(uri,
        mIsSyncOperation, mIsYieldAllowed);
    mIsYieldAllowed = false;
    mBatchOperation.add(builder.build());
  }

  /**
   * New insert cpo content provider operation . builder.
   *
   * @param uri             the uri
   * @param isSyncOperation the is sync operation
   * @param isYieldAllowed  the is yield allowed
   * @return the content provider operation . builder
   */
  public static ContentProviderOperation.Builder newInsertCpo(Uri uri,
      boolean isSyncOperation, boolean isYieldAllowed)
  {
    return ContentProviderOperation.newInsert(
        addCallerIsSyncAdapterParameter(uri, isSyncOperation))
        .withYieldAllowed(isYieldAllowed);
  }

  /**
   * New update cpo content provider operation . builder.
   *
   * @param uri             the uri
   * @param isSyncOperation the is sync operation
   * @param isYieldAllowed  the is yield allowed
   * @return the content provider operation . builder
   */
  public static ContentProviderOperation.Builder newUpdateCpo(Uri uri,
      boolean isSyncOperation, boolean isYieldAllowed)
  {
    return ContentProviderOperation.newUpdate(
        addCallerIsSyncAdapterParameter(uri, isSyncOperation))
        .withYieldAllowed(isYieldAllowed);
  }

  /**
   * New delete cpo content provider operation . builder.
   *
   * @param uri             the uri
   * @param isSyncOperation the is sync operation
   * @param isYieldAllowed  the is yield allowed
   * @return the content provider operation . builder
   */
  public static ContentProviderOperation.Builder newDeleteCpo(Uri uri,
      boolean isSyncOperation, boolean isYieldAllowed)
  {
    return ContentProviderOperation.newDelete(
        addCallerIsSyncAdapterParameter(uri, isSyncOperation))
        .withYieldAllowed(isYieldAllowed);
  }

  private static Uri addCallerIsSyncAdapterParameter(Uri uri,
      boolean isSyncOperation)
  {
    if (isSyncOperation) {
      // If we're in the middle of a real sync-adapter operation, then go
      // ahead
      // and tell the Contacts provider that we're the sync adapter. That
      // gives us some special permissions - like the ability to really
      // delete a contact, and the ability to clear the dirty flag.
      //
      // If we're not in the middle of a sync operation (for example, we
      // just
      // locally created/edited a new contact), then we don't want to use
      // the special permissions, and the system will automagically mark
      // the contact as 'dirty' for us!
      return uri
          .buildUpon()
          .appendQueryParameter(
              ContactsContract.CALLER_IS_SYNCADAPTER, "true")
          .build();
    }
    return uri;
  }
}
