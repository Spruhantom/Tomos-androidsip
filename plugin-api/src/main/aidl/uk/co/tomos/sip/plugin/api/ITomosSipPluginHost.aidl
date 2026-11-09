package uk.co.tomos.sip.plugin.api;

import uk.co.tomos.sip.plugin.api.ITomosSipPluginResultCallback;

/** Restricted host operations exposed to an approved plugin. */
interface ITomosSipPluginHost {
    /** Requests a host-mediated action; the host checks UID, capability and entitlement. */
    void requestAction(String actionJson, ITomosSipPluginResultCallback callback);

    /** Posts a plugin-scoped notification after capability validation. */
    void postNotification(String notificationJson, ITomosSipPluginResultCallback callback);
}
