package io.github.kormcrete.swingui.spi;

/**
 * The app plugin's defaults for written files. {@code export_test} writes under {@code <moduleDir>/src/test/java}
 * when that folder exists below the working directory, else under the working directory's {@code src/test/java},
 * into {@code packageName} when the call names no package; {@code -Dswingui.testsDir} overrides the folder.
 * A relative screenshot step path lands under {@code <moduleDir>/target/flow-screenshots} when {@code <moduleDir>}
 * exists. A null {@code moduleDir} means the working directory itself.
 */
public final class ExportDefaults {
    public final String moduleDir;
    public final String packageName;

    public ExportDefaults(String moduleDir, String packageName) {
        this.moduleDir = moduleDir;
        this.packageName = packageName;
    }
}
