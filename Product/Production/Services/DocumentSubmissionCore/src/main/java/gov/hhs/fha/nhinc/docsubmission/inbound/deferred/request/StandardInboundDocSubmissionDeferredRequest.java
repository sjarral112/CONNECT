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
package gov.hhs.fha.nhinc.docsubmission.inbound.deferred.request;

import gov.hhs.fha.nhinc.common.nhinccommon.AssertionType;
import gov.hhs.fha.nhinc.docsubmission.XDRAuditLogger;
import gov.hhs.fha.nhinc.docsubmission.XDRPolicyChecker;
import gov.hhs.fha.nhinc.docsubmission.adapter.deferred.request.error.proxy.AdapterDocSubmissionDeferredRequestErrorProxy;
import gov.hhs.fha.nhinc.docsubmission.adapter.deferred.request.error.proxy.AdapterDocSubmissionDeferredRequestErrorProxyObjectFactory;
import gov.hhs.fha.nhinc.nhinclib.NhincConstants;
import gov.hhs.fha.nhinc.nhinclib.NullChecker;
import gov.hhs.fha.nhinc.properties.PropertyAccessException;
import gov.hhs.fha.nhinc.properties.PropertyAccessor;
import gov.hhs.healthit.nhin.XDRAcknowledgementType;
import ihe.iti.xds_b._2007.ProvideAndRegisterDocumentSetRequestType;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * @author akong
 * 
 */
public class StandardInboundDocSubmissionDeferredRequest extends AbstractInboundDocSubmissionDeferredRequest {

    private Log log = LogFactory.getLog(StandardInboundDocSubmissionDeferredRequest.class);

    private XDRPolicyChecker policyChecker = new XDRPolicyChecker();
    private PassthroughInboundDocSubmissionDeferredRequest passthroughDSRequest = new PassthroughInboundDocSubmissionDeferredRequest();
    private PropertyAccessor propertyAccessor = PropertyAccessor.getInstance();
    private AdapterDocSubmissionDeferredRequestErrorProxyObjectFactory errorAdapterFactory = new AdapterDocSubmissionDeferredRequestErrorProxyObjectFactory();

    public StandardInboundDocSubmissionDeferredRequest() {
        super();
    }

    public StandardInboundDocSubmissionDeferredRequest(
            PassthroughInboundDocSubmissionDeferredRequest passthroughDSRequest, XDRPolicyChecker policyChecker,
            PropertyAccessor propertyAccessor, XDRAuditLogger auditLogger,
            AdapterDocSubmissionDeferredRequestErrorProxyObjectFactory errorAdapterFactory, Log log) {
        this.policyChecker = policyChecker;
        this.passthroughDSRequest = passthroughDSRequest;
        this.propertyAccessor = propertyAccessor;
        this.auditLogger = auditLogger;
        this.errorAdapterFactory = errorAdapterFactory;
        this.log = log;
    }

    XDRAcknowledgementType processDocSubmissionRequest(ProvideAndRegisterDocumentSetRequestType body,
            AssertionType assertion) {
        XDRAcknowledgementType response = null;

        String localHCID = getLocalHCID();
        if (isPolicyValid(body, assertion, localHCID)) {
            log.debug("Policy Check Succeeded");
            response = passthroughDSRequest.processDocSubmissionRequest(body, assertion);
        } else {
            log.error("Policy Check Failed");
            response = sendErrorToAdapter(body, assertion, "Policy Check Failed");
        }

        return response;
    }

    private String getLocalHCID() {
        String localHCID = null;
        try {
            localHCID = propertyAccessor.getProperty(NhincConstants.GATEWAY_PROPERTY_FILE,
                    NhincConstants.HOME_COMMUNITY_ID_PROPERTY);
        } catch (PropertyAccessException ex) {
            log.error("Exception while retrieving home community ID", ex);
        }

        return localHCID;
    }

    private boolean hasHomeCommunityId(AssertionType assertion) {
        if (assertion != null && assertion.getHomeCommunity() != null
                && NullChecker.isNotNullish(assertion.getHomeCommunity().getHomeCommunityId())) {
            return true;
        }
        return false;
    }

    private boolean isPolicyValid(ProvideAndRegisterDocumentSetRequestType request, AssertionType assertion,
            String receiverHCID) {

        if (!hasHomeCommunityId(assertion)) {
            log.warn("Failed policy check.  Received assertion does not have a home community id.");
            return false;
        }

        String senderHCID = assertion.getHomeCommunity().getHomeCommunityId();

        return policyChecker.checkXDRRequestPolicy(request, assertion, senderHCID, receiverHCID,
                NhincConstants.POLICYENGINE_INBOUND_DIRECTION);
    }

    private XDRAcknowledgementType sendErrorToAdapter(ProvideAndRegisterDocumentSetRequestType body,
            AssertionType assertion, String errMsg) {

        AdapterDocSubmissionDeferredRequestErrorProxy proxy = errorAdapterFactory
                .getAdapterDocSubmissionDeferredRequestErrorProxy();
        return proxy.provideAndRegisterDocumentSetBRequestError(body, errMsg, assertion);
    }

}