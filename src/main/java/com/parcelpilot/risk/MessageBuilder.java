package com.parcelpilot.risk;

/** Strategy for turning risk facts into a human sentence. The LLM variant only rephrases facts. */
public interface MessageBuilder {

    String build(RiskAssessment assessment, com.parcelpilot.weather.WeatherSnapshot weather);
}
