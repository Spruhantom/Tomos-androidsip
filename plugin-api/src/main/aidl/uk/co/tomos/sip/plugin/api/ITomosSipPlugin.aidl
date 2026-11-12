package uk.co.tomos.sip.plugin.api;

import uk.co.tomos.sip.plugin.api.ITomosSipPluginHost;

/** Implemented by an independently installed plugin APK. */
interface ITomosSipPlugin {
    /** Returns the plugin's JSON manifest. The host validates every field. */
    String getManifestJson();

    /** Called after the host has verified package identity and granted capabilities. */
    void onHostConnected(int hostApiVersion, ITomosSipPluginHost host);

    /** Called when the host is disconnecting or revoking this plugin session. */
    void onHostDisconnected();

    /** Delivers a redacted, bounded call lifecycle event. */
    void onCallEvent(String eventJson);
}
