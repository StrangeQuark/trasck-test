package com.strangequark.utility;

import io.github.cdimascio.dotenv.Dotenv;

public class EnvUtility {
    public static String getEnvVar(String varName) {
        String envVar = System.getenv(varName);
        if(envVar == null || envVar.isEmpty()) {
            Dotenv dotenv = Dotenv.load();
            envVar = dotenv.get(varName);
        }
        if(envVar == null || envVar.isEmpty()) {
            throw new IllegalStateException(varName + " is not set in environment variables or .env file.");
        }

        return envVar;
    }
}
