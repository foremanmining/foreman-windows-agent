package mn.foreman.windowsagent.foreman;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** An application manifest. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AppManifest {

    /** The application alias. */
    @JsonProperty("alias")
    public String alias;

    /** The app name. */
    @JsonProperty("app")
    public String app;

    /** The app configuration. */
    @JsonProperty("conf")
    public Conf conf;

    /** The executable. */
    @JsonProperty("executable")
    public String executable;

    /** The github info. */
    @JsonProperty("github")
    public Github github;

    /** The application version. */
    @JsonProperty("version")
    public String version;

    /** Whether or not the application applies to Windows. */
    @JsonProperty("windows")
    public boolean windows;

    /** An app's configuration. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Conf {

        /** The api key pattern. */
        @JsonProperty("apiKeyPattern")
        public String apiKeyPattern;

        /** The client ID pattern. */
        @JsonProperty("clientIdPattern")
        public String clientIdPattern;

        /** The conf file, if one exists. */
        @JsonProperty("file")
        public String file;
    }

    /** A model object for Github. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Github {

        /** The artifact name. */
        @JsonProperty("name")
        public String name;

        /** The zip url. */
        @JsonProperty("zipUrl")
        public String zipUrl;
    }
}