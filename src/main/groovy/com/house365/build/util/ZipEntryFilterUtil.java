package com.house365.build.util;

import com.android.annotations.NonNull;
import com.android.build.gradle.internal.packaging.PackagingFileAction;
import com.android.build.gradle.internal.packaging.ParsedPackagingOptions;
import com.android.builder.packaging.DuplicateFileException;
import com.android.builder.packaging.ZipAbortException;
import com.android.builder.packaging.ZipEntryFilter;
import com.google.common.collect.Lists;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

import static com.android.SdkConstants.DOT_CLASS;

/**
 * Created by ZhangZhenli on 2016/3/29.
 */
public class ZipEntryFilterUtil {

    private static final Logger mLogger = Logging.getLogger(ZipEntryFilterUtil.class);
    private static final Pattern pattern = Pattern.compile("META-INF/MANIFEST.MF");

    public static final class JarWhitoutRFilter extends PackagingFilter {

        public JarWhitoutRFilter(ParsedPackagingOptions packagingOptions, ZipEntryFilter parentFilter) {
            super(packagingOptions, parentFilter);
        }

        @Override
        public boolean checkEntry(String archivePath) throws ZipAbortException {
            return ZipEntryFilterUtil.checkEntry(ZipEntryFilterUtil.checkWithoutR(), archivePath) &&
                    super.checkEntry(archivePath);
        }
    }

    public static final class JarRFilter extends PackagingFilter {

        public JarRFilter(ParsedPackagingOptions packagingOptions, ZipEntryFilter parentFilter) {
            super(packagingOptions, parentFilter);
        }

        @Override
        public boolean checkEntry(String archivePath) throws ZipAbortException {
            return !ZipEntryFilterUtil.checkEntry(ZipEntryFilterUtil.checkWithoutR(), archivePath) &&
                    super.checkEntry(archivePath);
        }
    }

    private static boolean checkEntry(
            @NonNull List<Pattern> patterns,
            @NonNull String name) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(name).matches()) {
                return false;
            }
        }
        return true;
    }

    public static class PackagingFilter extends DuplicateZipFilter {

        HashSet<String> mAddedFirstFiles = new HashSet<String>();
        @NonNull
        private ZipEntryFilter parentFilter;

        private ParsedPackagingOptions packagingOptions;

        public PackagingFilter(ParsedPackagingOptions packagingOptions, ZipEntryFilter parentFilter) {
            this.packagingOptions = packagingOptions;
            this.parentFilter = parentFilter;
        }

        /**
         * Implementation of the {@link ZipEntryFilter} contract which only
         * cares about copying or ignoring files since merging is handled differently.
         *
         * @param archivePath the archive file path of the entry
         * @return true if the archive entry satisfies the filter, false otherwise.
         * @throws ZipAbortException
         */
        @Override
        public boolean checkEntry(@NonNull String archivePath)
                throws ZipAbortException {

            if (pattern.matcher(archivePath).matches()) {
                return false;
            }

            PackagingFileAction action = getPackagingAction(archivePath);
            switch (action) {
                case EXCLUDE:
                    return false;
                case PICK_FIRST:
                    if (!mAddedFirstFiles.contains(archivePath))
                        mAddedFirstFiles.add(archivePath);
                    else
                        return false;
                case MERGE:
                case NONE:
                    return super.checkEntry(archivePath) &&
                            (parentFilter != null ? parentFilter.checkEntry(archivePath) : true);
                default:
                    throw new RuntimeException("Unhandled action " + action);
            }
        }

        /**
         * Determine the user's intention for a particular archive entry.
         *
         * @param archivePath the archive entry
         * @return a {@link PackagingFileAction} as provided by the user in the build.gradle
         */
        @NonNull
        private PackagingFileAction getPackagingAction(@NonNull String archivePath) {
            if (packagingOptions != null) {
                return packagingOptions.getAction(archivePath);
            }
            return PackagingFileAction.NONE;
        }
    }


    /**
     * Filter to detect duplicate entries
     */
    public static class DuplicateZipFilter implements ZipEntryFilter {
        private final HashMap<String, File> mAddedFiles = new HashMap<String, File>();

        private File mInputFile;

        public void reset(File inputFile) {
            mInputFile = inputFile;
        }

        @Override
        public boolean checkEntry(String archivePath) throws ZipAbortException {
            mLogger.info("=> %s", archivePath);
            File duplicate = checkFileForDuplicate(archivePath);
            if (duplicate != null) {
                // we have a duplicate but it might be the same source file, in this case,
                // we just ignore the duplicate, and of course, we don't add it again.
                File potentialDuplicate = mInputFile;
                if (!duplicate.getAbsolutePath().equals(mInputFile.getAbsolutePath())) {
                    try {
                        throw new DuplicateFileException(archivePath, duplicate, mInputFile);
                    } catch (DuplicateFileException e) {
                        e.printStackTrace();
                    }
                }
                return false;
            } else {
                mAddedFiles.put(archivePath, mInputFile);
            }

            return true;
        }

        /**
         * Checks if the given path in the APK archive has not already been used and if it has been,
         * then returns a {@link File} object for the source of the duplicate
         *
         * @param archivePath the archive path to test.
         * @return A File object of either a file at the same location or an archive that contains a
         * file that was put at the same location.
         */
        private File checkFileForDuplicate(String archivePath) {
            return mAddedFiles.get(archivePath);
        }
    }


    /**
     * A filter to filter out binary files like .class
     */
    private static final class NoJavaClassZipFilter implements ZipEntryFilter {
        @NonNull
        private final ZipEntryFilter parentFilter;

        private NoJavaClassZipFilter(@NonNull ZipEntryFilter parentFilter) {
            this.parentFilter = parentFilter;
        }


        @Override
        public boolean checkEntry(String archivePath) throws ZipAbortException {
            return parentFilter.checkEntry(archivePath) && !archivePath.endsWith(DOT_CLASS);
        }
    }

    public static List<Pattern> checkWithoutR() {
        List<String> excludes = Lists.newArrayListWithExpectedSize(5);
        // these must be regexp to match the zip entries
        excludes.add(".*/R.class$");
        excludes.add(".*/R\\$(.*).class$");
//        excludes.add(packagePath + "/Manifest.class$");
//        excludes.add(packagePath + "/Manifest\\$(.*).class$");
        // create Pattern Objects.
        List<Pattern> patterns = Lists.newArrayListWithCapacity(excludes.size());
        for (String exclude : excludes) {
            patterns.add(Pattern.compile(exclude));
        }
        return patterns;
    }


}
