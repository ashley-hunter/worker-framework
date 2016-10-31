package com.hpe.caf.worker.testing;

/**
 * Created by ploch on 23/11/2015.
 */
public final class SettingNames {

    private SettingNames(){}

    public static final String inputFolder = "input.folder";
    public static final String expectedFolder = "expected.folder";
    public static final String processSubFolders = "process.subfolders";

    // New names for folders above. inputFolder and expectedFolder are now replaced with:
    // testCaseFolder and documentFolder;
    public static final String testCaseFolder = expectedFolder;
    public static final String documentFolder = inputFolder;

    public static final String dataStoreContainerId = "datastore.container.id";
    public static final String useDataStore = "datastore.enabled";

    public static final String dockerHostAddress = "docker.host.address";
    public static final String rabbitmqNodePort = "rabbitmq.node.port";
    public static final String rabbitmqCtrlPort = "rabbitmq.ctrl.port";

    public static final String taskTemplate = "task.template";

    public static final String storeTestCaseWithInput = "store.testcase.with.input";

    public static final String stopOnError = "stop.on.error";

    public static final String timeOutMs = "timeout.ms";

    public static final String createDebugMessage = "create.debug.message";

    // If set to true this option will cause an exception to be thrown if there are additional properties within the
    // actual result when compared to the expected. Defaults to true
    public static final String failOnUnknownProperty = "fail.on.unknown.property";

    public static final String testSourcefileBaseFolder = "test.sourcefile.base.folder";
}