package com.strangequark.utility;

import io.github.cdimascio.dotenv.Dotenv;

public class EnvUtility {
    private static final Dotenv DOTENV = Dotenv.configure()
            .ignoreIfMissing()
            .ignoreIfMalformed()
            .load();

    public static String getEnvVar(String varName) {
        String envVar = System.getenv(varName);
        if(envVar == null || envVar.isEmpty()) {
            envVar = DOTENV.get(varName);
        }
        if(envVar == null || envVar.isEmpty()) {
            throw new IllegalStateException(varName + " is not set in environment variables or .env file.");
        }

        return envVar;
    }

    public static String getEnvVar(String varName, String defaultValue) {
        String envVar = System.getenv(varName);
        if(envVar == null || envVar.isEmpty()) {
            envVar = DOTENV.get(varName);
        }
        return envVar == null || envVar.isEmpty() ? defaultValue : envVar;
    }
}
