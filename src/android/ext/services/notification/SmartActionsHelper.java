/**
 * Copyright (C) 2018 The Android Open Source Project
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
package android.ext.services.notification;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Notification;
import android.app.Person;
import android.app.RemoteAction;
import android.content.Context;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.Process;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.view.textclassifier.ConversationActions;
import android.view.textclassifier.TextClassification;
import android.view.textclassifier.TextClassificationManager;
import android.view.textclassifier.TextClassifier;
import android.view.textclassifier.TextLinks;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.stream.Collectors;

public class SmartActionsHelper {
    private static final ArrayList<Notification.Action> EMPTY_ACTION_LIST = new ArrayList<>();
    private static final ArrayList<CharSequence> EMPTY_REPLY_LIST = new ArrayList<>();

    // If a notification has any of these flags set, it's inelgibile for actions being added.
    private static final int FLAG_MASK_INELGIBILE_FOR_ACTIONS =
            Notification.FLAG_ONGOING_EVENT
                    | Notification.FLAG_FOREGROUND_SERVICE
                    | Notification.FLAG_GROUP_SUMMARY
                    | Notification.FLAG_NO_CLEAR;
    private static final int MAX_ACTION_EXTRACTION_TEXT_LENGTH = 400;
    private static final int MAX_ACTIONS_PER_LINK = 1;
    private static final int MAX_SMART_ACTIONS = 3;
    private static final int MAX_SUGGESTED_REPLIES = 3;
    // TODO: Make this configurable.
    private static final int MAX_MESSAGES_TO_EXTRACT = 5;

    private static final ConversationActions.TypeConfig TYPE_CONFIG =
            new ConversationActions.TypeConfig.Builder().setIncludedTypes(
                    Collections.singletonList(ConversationActions.TYPE_TEXT_REPLY))
                    .includeTypesFromTextClassifier(false)
                    .build();
    private static final List<String> HINTS =
            Collections.singletonList(ConversationActions.HINT_FOR_NOTIFICATION);

    SmartActionsHelper() {
    }

    /**
     * Adds action adjustments based on the notification contents.
     */
    @NonNull
    ArrayList<Notification.Action> suggestActions(@Nullable Context context,
            @NonNull NotificationEntry entry, @NonNull AssistantSettings settings) {
        if (!settings.mGenerateActions) {
            return EMPTY_ACTION_LIST;
        }
        if (!isEligibleForActionAdjustment(entry)) {
            return EMPTY_ACTION_LIST;
        }
        if (context == null) {
            return EMPTY_ACTION_LIST;
        }
        TextClassificationManager tcm = context.getSystemService(TextClassificationManager.class);
        if (tcm == null) {
            return EMPTY_ACTION_LIST;
        }
        List<ConversationActions.Message> messages = extractMessages(entry.getNotification());
        if (messages.isEmpty()) {
            return EMPTY_ACTION_LIST;
        }
        // TODO: Move to TextClassifier.suggestConversationActions once it is ready.
        return suggestActionsFromText(
                tcm, messages.get(messages.size() - 1).getText(), MAX_SMART_ACTIONS);
    }

    ArrayList<CharSequence> suggestReplies(@Nullable Context context,
            @NonNull NotificationEntry entry, @NonNull AssistantSettings settings) {
        if (!settings.mGenerateReplies) {
            return EMPTY_REPLY_LIST;
        }
        if (!isEligibleForReplyAdjustment(entry)) {
            return EMPTY_REPLY_LIST;
        }
        if (context == null) {
            return EMPTY_REPLY_LIST;
        }
        TextClassificationManager tcm = context.getSystemService(TextClassificationManager.class);
        if (tcm == null) {
            return EMPTY_REPLY_LIST;
        }
        List<ConversationActions.Message> messages = extractMessages(entry.getNotification());
        if (messages.isEmpty()) {
            return EMPTY_REPLY_LIST;
        }
        ConversationActions.Request request =
                new ConversationActions.Request.Builder(messages)
                        .setMaxSuggestions(MAX_SUGGESTED_REPLIES)
                        .setHints(HINTS)
                        .setTypeConfig(TYPE_CONFIG)
                        .build();

        TextClassifier textClassifier = tcm.getTextClassifier();
        List<ConversationActions.ConversationAction> conversationActions =
                textClassifier.suggestConversationActions(request).getConversationActions();

        return conversationActions.stream()
                .map(conversationAction -> conversationAction.getTextReply())
                .filter(textReply -> !TextUtils.isEmpty(textReply))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    /**
     * Returns whether a notification is eligible for action adjustments.
     *
     * <p>We exclude system notifications, those that get refreshed frequently, or ones that relate
     * to fundamental phone functionality where any error would result in a very negative user
     * experience.
     */
    private boolean isEligibleForActionAdjustment(@NonNull NotificationEntry entry) {
        Notification notification = entry.getNotification();
        String pkg = entry.getSbn().getPackageName();
        if (!Process.myUserHandle().equals(entry.getSbn().getUser())) {
            return false;
        }
        if ((notification.flags & FLAG_MASK_INELGIBILE_FOR_ACTIONS) != 0) {
            return false;
        }
        if (TextUtils.isEmpty(pkg) || pkg.equals("android")) {
            return false;
        }
        // For now, we are only interested in messages.
        return entry.isMessaging();
    }

    private boolean isEligibleForReplyAdjustment(@NonNull NotificationEntry entry) {
        if (!Process.myUserHandle().equals(entry.getSbn().getUser())) {
            return false;
        }
        String pkg = entry.getSbn().getPackageName();
        if (TextUtils.isEmpty(pkg) || pkg.equals("android")) {
            return false;
        }
        // For now, we are only interested in messages.
        if (!entry.isMessaging()) {
            return false;
        }
        // Does not make sense to provide suggested replies if it is not something that can be
        // replied.
        if (!entry.hasInlineReply()) {
            return false;
        }
        return true;
    }

    /** Returns the text most salient for action extraction in a notification. */
    @Nullable
    private List<ConversationActions.Message> extractMessages(@NonNull Notification notification) {
        Parcelable[] messages = notification.extras.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (messages == null || messages.length == 0) {
            return Arrays.asList(new ConversationActions.Message.Builder(
                    ConversationActions.Message.PERSON_USER_REMOTE)
                    .setText(notification.extras.getCharSequence(Notification.EXTRA_TEXT))
                    .build());
        }
        Person localUser = notification.extras.getParcelable(Notification.EXTRA_MESSAGING_PERSON);
        Deque<ConversationActions.Message> extractMessages = new ArrayDeque<>();
        for (int i = messages.length - 1; i >= 0; i--) {
            Notification.MessagingStyle.Message message =
                    Notification.MessagingStyle.Message.getMessageFromBundle((Bundle) messages[i]);
            if (message == null) {
                continue;
            }
            Person senderPerson = message.getSenderPerson();
            // Skip encoding once the sender is missing as it is important to distinguish
            // local user and remote user when generating replies.
            if (senderPerson == null) {
                break;
            }
            Person author = localUser != null && localUser.equals(senderPerson)
                    ? ConversationActions.Message.PERSON_USER_LOCAL : senderPerson;
            extractMessages.push(new ConversationActions.Message.Builder(author)
                    .setText(message.getText())
                    .setReferenceTime(
                            ZonedDateTime.ofInstant(Instant.ofEpochMilli(message.getTimestamp()),
                                    ZoneOffset.systemDefault()))
                    .build());
            if (extractMessages.size() >= MAX_MESSAGES_TO_EXTRACT) {
                break;
            }
        }
        return new ArrayList<>(extractMessages);
    }

    /** Returns a list of actions to act on entities in a given piece of text. */
    @NonNull
    private ArrayList<Notification.Action> suggestActionsFromText(
            @NonNull TextClassificationManager tcm, @Nullable CharSequence text,
            int maxSmartActions) {
        if (TextUtils.isEmpty(text)) {
            return EMPTY_ACTION_LIST;
        }
        TextClassifier textClassifier = tcm.getTextClassifier();

        // We want to process only text visible to the user to avoid confusing suggestions, so we
        // truncate the text to a reasonable length. This is particularly important for e.g.
        // email apps that sometimes include the text for the entire thread.
        text = text.subSequence(0, Math.min(text.length(), MAX_ACTION_EXTRACTION_TEXT_LENGTH));

        // Extract all entities.
        TextLinks.Request textLinksRequest = new TextLinks.Request.Builder(text)
                .setEntityConfig(
                        TextClassifier.EntityConfig.createWithHints(
                                Collections.singletonList(
                                        TextClassifier.HINT_TEXT_IS_NOT_EDITABLE)))
                .build();
        TextLinks links = textClassifier.generateLinks(textLinksRequest);
        ArrayMap<String, Integer> entityTypeFrequency = getEntityTypeFrequency(links);

        ArrayList<Notification.Action> actions = new ArrayList<>();
        for (TextLinks.TextLink link : links.getLinks()) {
            // Ignore any entity type for which we have too many entities. This is to handle the
            // case where a notification contains e.g. a list of phone numbers. In such cases, the
            // user likely wants to act on the whole list rather than an individual entity.
            if (link.getEntityCount() == 0
                    || entityTypeFrequency.get(link.getEntity(0)) != 1) {
                continue;
            }

            // Generate the actions, and add the most prominent ones to the action bar.
            TextClassification classification =
                    textClassifier.classifyText(
                            new TextClassification.Request.Builder(
                                    text, link.getStart(), link.getEnd()).build());
            int numOfActions = Math.min(
                    MAX_ACTIONS_PER_LINK, classification.getActions().size());
            for (int i = 0; i < numOfActions; ++i) {
                RemoteAction action = classification.getActions().get(i);
                actions.add(
                        new Notification.Action.Builder(
                                action.getIcon(),
                                action.getTitle(),
                                action.getActionIntent())
                                .build());
                // We have enough smart actions.
                if (actions.size() >= maxSmartActions) {
                    return actions;
                }
            }
        }
        return actions;
    }

    /**
     * Given the links extracted from a piece of text, returns the frequency of each entity
     * type.
     */
    @NonNull
    private ArrayMap<String, Integer> getEntityTypeFrequency(@NonNull TextLinks links) {
        ArrayMap<String, Integer> entityTypeCount = new ArrayMap<>();
        for (TextLinks.TextLink link : links.getLinks()) {
            if (link.getEntityCount() == 0) {
                continue;
            }
            String entityType = link.getEntity(0);
            if (entityTypeCount.containsKey(entityType)) {
                entityTypeCount.put(entityType, entityTypeCount.get(entityType) + 1);
            } else {
                entityTypeCount.put(entityType, 1);
            }
        }
        return entityTypeCount;
    }
}
