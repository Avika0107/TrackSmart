package com.parcelpilot.model;

/**
 * API = live carrier/aggregator tracking; EMAIL_ONLY = retailer emails we cannot send to
 * third-party tracking APIs (e.g. Amazon internal TBA numbers);
 * EMAIL_PENDING = order confirmation received, waiting for the retailer's shipping email
 * to supply the tracking number.
 */
public enum TrackingMode { API, EMAIL_ONLY, EMAIL_PENDING }
