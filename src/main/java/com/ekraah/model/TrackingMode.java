package com.ekraah.model;

/**
 * API = live carrier/aggregator tracking; EMAIL_ONLY = retailer emails we cannot send to
 * third-party tracking APIs (e.g. Amazon internal TBA numbers);
 * EMAIL_PENDING = order confirmation received, waiting for the retailer's shipping email
 * to supply the tracking number;
 * PAGE_ONLY = imported by the browser extension from a retailer order page that shows only
 * the retailer's order id (e.g. Flipkart OD…): status and dates come from that page.
 */
public enum TrackingMode { API, EMAIL_ONLY, EMAIL_PENDING, PAGE_ONLY }
