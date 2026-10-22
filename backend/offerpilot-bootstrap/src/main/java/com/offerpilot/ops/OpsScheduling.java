package com.offerpilot.ops;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Safety/cleanup scheduling must remain enabled even when resume parsing workers are disabled. */
@Configuration
@EnableScheduling
public class OpsScheduling {}
