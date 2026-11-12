package uk.co.tomos.sip.plugin.api;

/** Result callback for an asynchronous host-mediated plugin operation. */
interface ITomosSipPluginResultCallback {
    void onResult(String resultJson);
}
