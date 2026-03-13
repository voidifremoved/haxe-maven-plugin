package com.rubberjam;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.Set;

@Mojo(name = "compile-haxe", defaultPhase = LifecyclePhase.GENERATE_SOURCES)
public class HaxeCompileMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "4.3.3", property = "haxeVersion")
    private String haxeVersion;

    @Parameter(defaultValue = "build.hxml", property = "hxmlFile")
    private String hxmlFile;

    @Override
    public void execute() throws MojoExecutionException {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            boolean isWindows = os.contains("win");
            boolean isMac = os.contains("mac");

            File haxeBaseDir = new File(project.getBuild().getDirectory(), "haxe-tool-" + haxeVersion);
            File haxeExecutable = findHaxeExecutable(haxeBaseDir, isWindows);

            if (haxeExecutable == null || !haxeExecutable.exists()) {
                getLog().info("Haxe " + haxeVersion + " not found locally. Downloading...");
                downloadAndExtractHaxe(haxeBaseDir, haxeVersion, isWindows, isMac);
                haxeExecutable = findHaxeExecutable(haxeBaseDir, isWindows);
            }

            if (haxeExecutable == null || !haxeExecutable.exists()) {
                throw new MojoExecutionException("Could not find extracted Haxe executable.");
            }

            if (!isWindows) {
                Set<PosixFilePermission> perms = new HashSet<>();
                perms.add(PosixFilePermission.OWNER_READ);
                perms.add(PosixFilePermission.OWNER_WRITE);
                perms.add(PosixFilePermission.OWNER_EXECUTE);
                perms.add(PosixFilePermission.GROUP_READ);
                perms.add(PosixFilePermission.GROUP_EXECUTE);
                perms.add(PosixFilePermission.OTHERS_READ);
                perms.add(PosixFilePermission.OTHERS_EXECUTE);
                Files.setPosixFilePermissions(haxeExecutable.toPath(), perms);

                // Also make haxelib executable if it exists
                File haxelibExec = new File(haxeExecutable.getParentFile(), "haxelib");
                if (haxelibExec.exists()) {
                    Files.setPosixFilePermissions(haxelibExec.toPath(), perms);
                }
            }

            getLog().info("Executing Haxe compiler...");
            ProcessBuilder pb = new ProcessBuilder(haxeExecutable.getAbsolutePath(), hxmlFile);
            pb.directory(project.getBasedir());
            pb.inheritIO();

            Process process = pb.start();
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                throw new MojoExecutionException("Haxe compilation failed with exit code: " + exitCode);
            }
            getLog().info("Haxe compilation successful!");

        } catch (Exception e) {
            throw new MojoExecutionException("Failed to run Haxe compiler", e);
        }
    }

    private File findHaxeExecutable(File baseDir, boolean isWindows) {
        if (!baseDir.exists() || !baseDir.isDirectory()) {
            return null;
        }

        String executableName = isWindows ? "haxe.exe" : "haxe";

        try {
            return Files.walk(baseDir.toPath())
                    .map(Path::toFile)
                    .filter(File::isFile)
                    .filter(f -> f.getName().equals(executableName))
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            getLog().warn("Error searching for Haxe executable", e);
            return null;
        }
    }

    private void downloadAndExtractHaxe(File targetDir, String version, boolean isWindows, boolean isMac) throws Exception {
        targetDir.mkdirs();

        String platformSuffix;
        String extension;
        if (isWindows) {
            platformSuffix = "win64";
            extension = ".zip";
        } else if (isMac) {
            platformSuffix = "osx";
            extension = ".tar.gz";
        } else {
            platformSuffix = "linux64";
            extension = ".tar.gz";
        }

        String fileName = "haxe-" + version + "-" + platformSuffix + extension;
        String downloadUrl = "https://github.com/HaxeFoundation/haxe/releases/download/" + version + "/" + fileName;

        File archiveFile = new File(targetDir, fileName);

        getLog().info("Downloading from: " + downloadUrl);
        FileUtils.copyURLToFile(new URL(downloadUrl), archiveFile, 10000, 10000);

        getLog().info("Extracting archive...");
        extractArchive(archiveFile, targetDir);

        archiveFile.delete();
    }

    private void extractArchive(File archiveFile, File destDir) throws Exception {
        boolean isZip = archiveFile.getName().endsWith(".zip");

        try (InputStream fi = Files.newInputStream(archiveFile.toPath());
             InputStream bi = new BufferedInputStream(fi)) {

             ArchiveInputStream i;
             if (isZip) {
                 i = new ZipArchiveInputStream(bi);
             } else {
                 InputStream gzi = new GzipCompressorInputStream(bi);
                 i = new TarArchiveInputStream(gzi);
             }

             try {
                 ArchiveEntry entry;
                 while ((entry = i.getNextEntry()) != null) {
                     if (!i.canReadEntryData(entry)) {
                         continue;
                     }
                     File f = new File(destDir, entry.getName());
                     if (entry.isDirectory()) {
                         if (!f.isDirectory() && !f.mkdirs()) {
                             throw new Exception("Failed to create directory " + f);
                         }
                     } else {
                         File parent = f.getParentFile();
                         if (!parent.isDirectory() && !parent.mkdirs()) {
                             throw new Exception("Failed to create directory " + parent);
                         }
                         try (OutputStream o = Files.newOutputStream(f.toPath())) {
                             IOUtils.copy(i, o);
                         }
                     }
                 }
             } finally {
                 i.close();
             }
        }
    }
}
