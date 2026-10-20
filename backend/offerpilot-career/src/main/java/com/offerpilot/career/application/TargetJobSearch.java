package com.offerpilot.career.application;
import com.fasterxml.jackson.databind.JsonNode;
public interface TargetJobSearch { JsonNode search(String owner, String target, String city); }
