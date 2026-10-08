package org.example.usage;

/** Fetches one {@link UsageSnapshot} from the usage endpoint. */
public interface UsageSource {

    /**
     * @param token the OAuth access token to send as a bearer token
     * @throws UsageFetchException if the request fails or is refused
     * @throws UsageParseException if the response is not a usage document
     */
    UsageSnapshot fetch(String token);
}
