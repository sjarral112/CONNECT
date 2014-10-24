/*
 * Copyright (c) 2012, United States Government, as represented by the Secretary of Health and Human Services.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *     * Redistributions of source code must retain the above
 *       copyright notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the documentation
 *       and/or other materials provided with the distribution.
 *     * Neither the name of the United States Government nor the
 *       names of its contributors may be used to endorse or promote products
 *       derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE UNITED STATES GOVERNMENT BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package gov.hhs.fha.nhinc.direct.messagemonitoring.impl;

import gov.hhs.fha.nhinc.direct.edge.proxy.DirectEdgeProxy;
import gov.hhs.fha.nhinc.direct.event.DirectEventLogger;
import gov.hhs.fha.nhinc.direct.event.DirectEventType;
import gov.hhs.fha.nhinc.direct.messagemonitoring.dao.MessageMonitoringDAO;
import gov.hhs.fha.nhinc.direct.messagemonitoring.dao.MessageMonitoringDAOException;
import gov.hhs.fha.nhinc.direct.messagemonitoring.dao.impl.MessageMonitoringDAOImpl;
import gov.hhs.fha.nhinc.direct.messagemonitoring.domain.MonitoredMessage;
import gov.hhs.fha.nhinc.direct.messagemonitoring.domain.MonitoredMessageNotification;
import gov.hhs.fha.nhinc.direct.messagemonitoring.util.MessageMonitoringUtil;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.mail.MessagingException;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.nhindirect.common.tx.TxUtil;
import org.nhindirect.common.tx.model.TxMessageType;

/**
 * All the Message Monitoring API services are exposed through this class. This class will maintain a cache to store all
 * the active messages that are sent out waiting for response.
 *
 * @author Naresh Subramanyan
 */
public class MessageMonitoringAPI {

    private static final Logger LOG = Logger.getLogger(MessageMonitoringAPI.class);
    //messageId is the key and Trackmessage object the value
    Map<String, MonitoredMessage> messageMonitoringCache = null;
    private static final String STATUS_PENDING = "Pending";
    private static final String STATUS_ERROR = "Error";
    private static final String STATUS_COMPLETED = "Completed";
    private static final String STATUS_PROCESSED = "Processed";
    private static final String STATUS_DISPATCHED = "Dispatched";

    public MessageMonitoringAPI() {
        //set the default value
        messageMonitoringCache = new HashMap<String, MonitoredMessage>();
        //Load the cahce from the database
        buildCache();
    }

    private static class SingletonHolder {

        public static final MessageMonitoringAPI INSTANCE = new MessageMonitoringAPI();
    }

    public static MessageMonitoringAPI getInstance() {
        return SingletonHolder.INSTANCE;
    }

    public void updateIncomingMessageNotificationStatus(MimeMessage message) {
        //Always
        //check if the message monitoring is enabled
        if (!MessageMonitoringUtil.isMessageMonitoringEnabled()) {
            LOG.debug("Message Monitoring is not enabled.");
            return;
        }
        LOG.debug("Message Monitoring is enabled.");

        try {
            //find out if its a successful or failed message
            boolean isMDNProcessed = MessageMonitoringUtil.isIncomingMessageMDNProcessed(message);
            boolean isMDNDispatched = MessageMonitoringUtil.isIncomingMessageMDNDispatched(message);

            String parentMessageId = MessageMonitoringUtil.getParentMessageId(message);
            MonitoredMessage tm = messageMonitoringCache.get(parentMessageId);

            //if the message is not there then ignore it for now
            if (tm == null) {
                LOG.debug("Not able to find the message in the cache..may be its a incoming message or junk message");
                return;
            }

            //ignore any message that comes if the status not in Pending
            if (!(tm.getStatus().equalsIgnoreCase(STATUS_PENDING) || tm.getStatus().equalsIgnoreCase(STATUS_PROCESSED))) {
                return;
            }
            //get the mail sender
            InternetAddress sender = (InternetAddress) message.getSender();
            if (sender == null) {
                InternetAddress[] fromAddresses = (InternetAddress[]) message.getFrom();
                sender = fromAddresses[0];
            }
            String senderMailId = sender.getAddress();

            MonitoredMessageNotification tmn = getTrackmessagenotification(tm, senderMailId);
            //check if its a MDN or DSN
            //if its an DSN then set the status to Error
            if (TxUtil.getMessageType(message).equals(TxMessageType.DSN)) {
                if (tmn == null) {
                    tm.setStatus(STATUS_ERROR);
                } else {
                    tmn.setStatus(STATUS_ERROR);
                }
            } //if its an MDN and also if delivery is requested
            else if (tm.getDeliveryrequested()) {
                //Update only if MDN is dispatched
                if (isMDNDispatched) {
                    //update the status to completed
                    tmn.setStatus(STATUS_COMPLETED);
                } else { //Update the status to Processed
                    tmn.setStatus(STATUS_PROCESSED);
                }
            } else if (isMDNDispatched | isMDNProcessed) {
                tmn.setStatus(STATUS_COMPLETED);
            } else { //if error
                tmn.setStatus(STATUS_ERROR);
            }
            Date updatedTime = new Date();
            if (getIncomingMessagesReceivedStatus(tm).equalsIgnoreCase(STATUS_PENDING)
                || getIncomingMessagesReceivedStatus(tm).equalsIgnoreCase(STATUS_PROCESSED)) {
                tmn.setUpdatetime(updatedTime);
                getMessageMonitoringDAO().updateMessageNotification(tmn);
            } else {
                //set the status to Completed or Error
                tm.setStatus(getIncomingMessagesReceivedStatus(tm));
                tm.setUpdatetime(updatedTime);
                getMessageMonitoringDAO().updateOutgoingMessage(tm);
            }
        } catch (MessagingException ex) {
            LOG.info("Failed:" + ex.getMessage());
        } catch (MessageMonitoringDAOException mde) {
            LOG.info("Failed:" + mde.getMessage());
        }
    }

    private void updateOutgoingMessage(MonitoredMessage trackMessage, boolean failed) {
        //set the status
        trackMessage.setStatus(failed ? STATUS_ERROR : STATUS_COMPLETED);

    }

    public void addOutgoingMessage(MimeMessage message, boolean failed, String errorMessage) {
        //Always check if message monitoring enabled
        if (!MessageMonitoringUtil.isMessageMonitoringEnabled()) {
            LOG.debug("Message Monitoring is not enabled.");
            return;
        }
        try {
            //get the all recipients
            InternetAddress recipients[] = (InternetAddress[]) message.getAllRecipients();

            //get the mail sender
            InternetAddress sender = (InternetAddress) message.getSender();
            String senderMailId = sender.getAddress();
            //Mail Subject
            String mailSubject = message.getSubject();
            //get the message id
            String messageId = message.getMessageID();
            //created time
            Date createdTime = new Date();

            boolean deliveryRequested = MessageMonitoringUtil.isNotificationRequestedByEdge(message);
            //Create the track Message domain
            MonitoredMessage tm = new MonitoredMessage();
            tm.setSubject(mailSubject);
            tm.setSenderemailid(senderMailId);
            tm.setMessageid(messageId);
            tm.setStatus(failed ? STATUS_ERROR : STATUS_PENDING);
            tm.setDeliveryrequested(deliveryRequested);
            tm.setCreatetime(createdTime);
            tm.setUpdatetime(createdTime);
            ArrayList recipientsList = new ArrayList();
            Set messageNotificationSet = new HashSet();
            for (InternetAddress address : recipients) {
                String emailId = address.getAddress();
                //create the track message notification objects
                MonitoredMessageNotification tmn = new MonitoredMessageNotification();
                tmn.setCreatetime(createdTime);
                tmn.setUpdatetime(createdTime);
                tmn.setEmailid(emailId);
                tmn.setStatus(failed ? STATUS_ERROR : STATUS_PENDING);
                messageNotificationSet.add(tmn);
                tmn.setMonitoredmessage(tm);
                recipientsList.add(emailId);
            }
            tm.setRecipients(StringUtils.join(recipientsList, ","));
            tm.setMonitoredmessagenotifications(messageNotificationSet);

            //call the dao to persist the date
            getMessageMonitoringDAO().addOutgoingMessage(tm);

            messageMonitoringCache.put(messageId, tm);

        } catch (MessagingException ex) {
            LOG.info("Failed:" + ex.getMessage());
        } catch (MessageMonitoringDAOException ex) {
            LOG.info("Failed:" + ex.getMessage());
        }
    }

    /**
     * Build the Message Monitoring cache from the database tables. This will be called when the module is initiated.
     *
     */
    private void buildCache() {
        LOG.debug("Inside buildCache");
        //Always check if message monitoring enabled
        if (!MessageMonitoringUtil.isMessageMonitoringEnabled()) {
            LOG.debug("Message Monitoring is not enabled.");
            return;
        }
        LOG.debug("Message Monitoring is enabled.");
        //get all the Pending rows and add it to the cache
        List<MonitoredMessage> pendingMessages = getAllPendingMessagesFromDatabase();
        LOG.debug("Total cache rows from database:" + pendingMessages.size());
        //clear the cache before loading the data from database
        clearCache();
        //load the pending outgoing messages to the cache
        for (MonitoredMessage trackMessage : pendingMessages) {
            messageMonitoringCache.put(trackMessage.getMessageid(), trackMessage);
            LOG.debug("Total child rows for the messageId:" + trackMessage.getMonitoredmessagenotifications().size());
        }
        LOG.debug("Exiting buildCache.");
    }

    /**
     * Clear the cache.
     *
     */
    public void clearCache() {
        messageMonitoringCache = new HashMap();
    }

    /**
     * Returns all the Successfully Completed outbound messages.
     *
     * @return List
     */
    public List<MonitoredMessage> getAllCompletedMessages() {
        List<MonitoredMessage> completedMessages = new ArrayList<MonitoredMessage>();
        //loop through the cache
        for (MonitoredMessage trackMessage : messageMonitoringCache.values()) {
            if (trackMessage.getStatus().equals(STATUS_COMPLETED)) {
                completedMessages.add(trackMessage);
            }
        }
        return completedMessages;
    }

    /**
     * Returns all the Successfully Completed outbound messages.
     *
     * @return List
     */
    public List<MonitoredMessage> getAllPendingMessages() {
        List<MonitoredMessage> pendingMessages = new ArrayList<MonitoredMessage>();
        //loop through the cache
        for (MonitoredMessage trackMessage : messageMonitoringCache.values()) {
            if (trackMessage.getStatus().equals(STATUS_PENDING)) {
                pendingMessages.add(trackMessage);
            }
        }
        return pendingMessages;
    }

    /**
     * Returns all the Successfully Completed outbound messages.
     *
     * @return List
     */
    public List<MonitoredMessage> getAllPendingMessagesFromDatabase() {
        return getMessageMonitoringDAO().getAllPendingMessages();
    }

    /**
     * Returns all the Failed outbound messages. 1. Processed not received for one or more recipients 2. Dispatched not
     * received for one or more recipients if notification requested by edge. 3. Got Failed DSN/MDN for one or more
     * recipients
     *
     * @return List
     */
    public List<MonitoredMessage> getAllFailedMessages() {
        //loop through the list and find all the pending messages
        List<MonitoredMessage> failedMessages = new ArrayList<MonitoredMessage>();
        //loop through the cache
        for (MonitoredMessage trackMessage : messageMonitoringCache.values()) {
            if (trackMessage.getStatus().equals(STATUS_ERROR)) {
                failedMessages.add(trackMessage);
            } else if (trackMessage.getStatus().equals(STATUS_PENDING)) {
                //if its pending & if its elapsed then
                //change the status to Error
            }
        }
        return failedMessages;
    }

    public boolean updateMessageMonitoringRetryCount(MimeMessage message) {

        try {
            //get the message id
            String messageId = message.getMessageID();

            //check if the retry limit has reached
            if (isRetryLimitReached(messageMonitoringCache.get(messageId))) {
                //update the cache
                //update the database
            }
            //its already there, just update the
            String emailId = null;
            Date updatedTime = new Date();
            //create the track message notification objects
            MonitoredMessageNotification tmn = new MonitoredMessageNotification();
            tmn.setUpdatetime(updatedTime);
            tmn.setEmailid(emailId);
            tmn.setStatus(STATUS_PENDING);
        } catch (MessagingException ex) {
            LOG.error(ex.getMessage());
        }
        return false;
    }

    //clear the cache
    //clear the database entries
    public void deleteCompletedOutgoingMessages() {
        //loop through the completed or Error List and delete the rows
    }

    protected MessageMonitoringDAO getMessageMonitoringDAO() {
        return MessageMonitoringDAOImpl.getInstance();
    }

    public boolean isRetryOutgoingMessage(MimeMessage message) {
        String messageId = MessageMonitoringUtil.getParentMessageId(message);
        return messageMonitoringCache.containsKey(messageId);
    }

    private boolean isRetryLimitReached(MonitoredMessage trackMessage) {
        //TODO
        //get the outgoing retry count

        //get the retried count from the cache
        //getTrackmessagenotification(trackMessage).
        //if the retry limit has reached, then update the status to "Error"
        //update the cache and also the trackMessage
        return true;
    }

    public MonitoredMessageNotification getTrackmessagenotification(MonitoredMessage trackMessage) throws MessagingException {
        //assuming only one recipient
        String emailId = trackMessage.getRecipients();

        Iterator iterator = trackMessage.getMonitoredmessagenotifications().iterator();
        // check values
        while (iterator.hasNext()) {
            MonitoredMessageNotification tmn = (MonitoredMessageNotification) iterator.next();
            //get the correspoding email id
            if (tmn.getEmailid().equalsIgnoreCase(emailId)) {
                return tmn;
            }
        }
        return null;
    }

    public MonitoredMessageNotification getTrackmessagenotification(MonitoredMessage trackMessage, String emailId) throws MessagingException {
        Iterator iterator = trackMessage.getMonitoredmessagenotifications().iterator();
        // check values
        while (iterator.hasNext()) {
            MonitoredMessageNotification tmn = (MonitoredMessageNotification) iterator.next();
            //get the correspoding email id
            if (tmn.getEmailid().equalsIgnoreCase(emailId)) {
                return tmn;
            }
        }
        return null;
    }

    private String getIncomingMessagesReceivedStatus(MonitoredMessage trackMessage) {
        boolean failed = false;
        boolean processed = false;

        //if the trackMessage status is Error then return ERROR
        if (trackMessage.getStatus().equalsIgnoreCase(STATUS_ERROR)) {
            return STATUS_ERROR;
        }

        //loop through the incoming message and return STATUS_COMPLETED or STATUS_PENDING or STATUS_FAILED
        Iterator iterator = trackMessage.getMonitoredmessagenotifications().iterator();
        // check values

        while (iterator.hasNext()) {
            MonitoredMessageNotification tmn = (MonitoredMessageNotification) iterator.next();
            //get the correspoding email id
            if (tmn.getStatus().equalsIgnoreCase(STATUS_PENDING)) {
                return STATUS_PENDING;
            } else if (tmn.getStatus().equalsIgnoreCase(STATUS_PROCESSED)) {
                processed = true;
            } else if (tmn.getStatus().equalsIgnoreCase(STATUS_ERROR)) {
                failed = true;
            }
        }
        //If not failed rows then retun it as PROCESSED
        if (failed) {
            return STATUS_ERROR;
        } else if (processed) {
            return STATUS_PROCESSED;
        } else {
            return STATUS_COMPLETED;
        }
    }

    public boolean isAllIncomingMessagesReceived(MimeMessage message) {
        String parentMessageId = MessageMonitoringUtil.getParentMessageId(message);
        MonitoredMessage tm = messageMonitoringCache.get(parentMessageId);

        if (tm != null) {
            return tm.getStatus().equalsIgnoreCase(STATUS_COMPLETED) || tm.getStatus().equalsIgnoreCase(STATUS_ERROR);
        }
        //if not able to find then return ture
        //TODO: revist this
        return true;
    }

    /**
     * This method is called by the poller task to monitor & update the message status and also to notify the edge
     * client with respective status of the
     *
     */
    public void process() {
        LOG.debug("Inside Message Monitoring API process() method.");

        //Always check if the message monitoring is enabled
        if (!MessageMonitoringUtil.isMessageMonitoringEnabled()) {
            LOG.debug("Message Monitoring is not enabled.");
            return;
        }
        //check all the pending messages and update the status
        //1. Check if the message is elaspsed and yes then update the status to Failed
        //   else Completed
        //2. Check all the completed /failed messages and set the status
        //    to Completed or Failed
        checkAndUpdateMessageStatus();
        try {
            //send notification to all the completed
            //or failed messages
            //delete the notified message
            processAllMessages();
        } catch (MessageMonitoringDAOException ex) {
            LOG.debug("Error in Message Monitoring API process()." + ex.getMessage());
        }
        LOG.debug("Exiting Message Monitoring API process() method.");
    }

    private void checkAndUpdateMessageStatus() {
        LOG.debug("Exiting Message Monitoring API checkAndUpdateMessageStatus() method.");
        //get the pending message list
        List<MonitoredMessage> pendingMessages = getAllPendingMessages();

        for (MonitoredMessage trackMessage : pendingMessages) {
            //check if the processed message is received, if not check the time limit
            //reached
            if (trackMessage.getStatus().equals(STATUS_PENDING) && getIncomingMessagesReceivedStatus(trackMessage).equals(STATUS_PENDING)) {
                if (MessageMonitoringUtil.isProcessedMDNReceiveTimeLapsed(trackMessage.getCreatetime())) {
                    LOG.debug("Processed MDN not received on time for the message ID:" + trackMessage.getMessageid());
                    //update the status to Error
                    updateOutgoingMessage(trackMessage, true);
                }//process the next pending message
            }//if the message status is processed then check if the time limit reached for dispatched
            else if (trackMessage.getStatus().equals(STATUS_PENDING) && getIncomingMessagesReceivedStatus(trackMessage).equals(STATUS_PROCESSED)) {
                if (MessageMonitoringUtil.isDispatchedMDNReceiveTimeLapsed(trackMessage.getCreatetime())) {
                    LOG.debug("Dispatched MDN not received on time for the message ID:" + trackMessage.getMessageid());
                    //update the status to Error
                    updateOutgoingMessage(trackMessage, true);
                }//process the next pending message
            }
        }
        LOG.debug("Exiting Message Monitoring API checkAndUpdateMessageStatus() method.");
    }

    public void processAllMessages() throws MessageMonitoringDAOException {
        LOG.debug("Inside Message Monitoring API checkAndUpdateMessageStatus() method.");
        //********FAILED MESSAGES***********
        //get all the failed messages
        List<MonitoredMessage> failedMessages = getAllFailedMessages();
        for (MonitoredMessage trackMessage : failedMessages) {
            //send out a Failed notification to the edge
            sendFailedEdgeNotification(trackMessage);
            //delete the message
            MessageMonitoringDAOImpl.getInstance().deleteCompletedMessages(trackMessage);
            messageMonitoringCache.remove(trackMessage.getMessageid());
            LOG.debug("Completed message deleted. Message ID:" + trackMessage.getMessageid());
        }
        //********COMPLETED MESSAGES********
        //get all the completed messages
        List<MonitoredMessage> completedMessages = getAllCompletedMessages();
        for (MonitoredMessage trackMessage : completedMessages) {
            //send out a successful notification
            sendSuccessEdgeNotification(trackMessage);
            //delete the message
            MessageMonitoringDAOImpl.getInstance().deleteCompletedMessages(trackMessage);
            messageMonitoringCache.remove(trackMessage.getMessageid());
            LOG.debug("Completed message deleted. Message ID:" + trackMessage.getMessageid());
        }
        LOG.debug("Exiting Message Monitoring API checkAndUpdateMessageStatus() method.");
    }

    /**
     * Sends out a Successful SMTP edge notification.
     *
     * @param trackMessage
     */
    public void sendSuccessEdgeNotification(MonitoredMessage trackMessage) {
        LOG.debug("Inside Message Monitoring API sendSuccessEdgeNotification() method.");

        String subject = MessageMonitoringUtil.getSuccessfulMessageSubjectPrefix() + trackMessage.getSubject();
        String emailText = MessageMonitoringUtil.getSuccessfulMessageEmailText() + trackMessage.getRecipients();
        String postmasterEmailId = MessageMonitoringUtil.getDomainPostmasterEmailId() + "@" + MessageMonitoringUtil.getDomainFromEmail(trackMessage.getSenderemailid());
        //logic goes here
        DirectEdgeProxy proxy = MessageMonitoringUtil.getDirectEdgeProxy();
        MimeMessage message = null;
        try {
            message = MessageMonitoringUtil.createMimeMessage(postmasterEmailId, subject, trackMessage.getSenderemailid(), emailText, trackMessage.getMessageid());
            proxy.provideAndRegisterDocumentSetB(message);
            //Log the failed QOS event
            getDirectEventLogger().log(DirectEventType.DIRECT_EDGE_NOTIFICATION_SUCCESSFUL, message);
        } catch (AddressException ex) {
            LOG.error(ex.getMessage());
            //if error then log a error event
            logErrorEvent(message, ex.getMessage());
        } catch (MessagingException ex) {
            LOG.error(ex.getMessage());
            //if error then log a error event
            logErrorEvent(message, ex.getMessage());
        }
        LOG.debug("Exiting Message Monitoring API sendSuccessEdgeNotification() method.");
    }

    /**
     * Sends out a Failed SMTP edge notification.
     *
     * @return List
     */
    private void sendFailedEdgeNotification(MonitoredMessage trackMessage) {
        LOG.debug("Inside Message Monitoring API sendFailedEdgeNotification() method.");
        String subject = MessageMonitoringUtil.getFailedMessageSubjectPrefix() + trackMessage.getSubject();
        String emailText = MessageMonitoringUtil.getFailedMessageEmailText() + trackMessage.getRecipients();
        String postmasterEmailId = MessageMonitoringUtil.getDomainPostmasterEmailId() + "@" + MessageMonitoringUtil.getDomainFromEmail(trackMessage.getSenderemailid());
        //logic goes here
        DirectEdgeProxy proxy = MessageMonitoringUtil.getDirectEdgeProxy();
        MimeMessage message = null;
        try {
            message = MessageMonitoringUtil.createMimeMessage(postmasterEmailId, subject, trackMessage.getSenderemailid(), emailText, trackMessage.getMessageid());
            proxy.provideAndRegisterDocumentSetB(message);
            //Log the failed QOS event
            getDirectEventLogger().log(DirectEventType.DIRECT_EDGE_NOTIFICATION_FAILED, message);
        } catch (AddressException ex) {
            LOG.error(ex.getMessage());
            //Log the error
            logErrorEvent(message, ex.getMessage());
        } catch (MessagingException ex) {
            LOG.error(ex.getMessage());
            //Log the error
            logErrorEvent(message, ex.getMessage());
        }
        LOG.debug("Exiting Message Monitoring API sendFailedEdgeNotification() method.");
    }

    /**
     * Returns the Direct event logger instance.
     *
     * @return the directEventLogger
     */
    private DirectEventLogger getDirectEventLogger() {
        return DirectEventLogger.getInstance();
    }

    public void logErrorEvent(MimeMessage message, String errorMessage) {
        if (message != null) {
            getDirectEventLogger().log(DirectEventType.DIRECT_ERROR, message, errorMessage);
        }
    }

}
