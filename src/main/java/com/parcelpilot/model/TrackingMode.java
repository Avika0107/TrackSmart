package com.parcelpilot.model;

/**
 * API = live carrier/aggregator tracking; EMAIL_ONLY = retailer emails we cannot send to
 * third-party tracking APIs (e.g. Amazon internal TBA numbers).
 */
public enum TrackingMode { API, EMAIL_ONLY }
