/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License (the "License").
 * You may not use this file except in compliance with the License.
 *
 * See LICENSE.txt included in this distribution for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at LICENSE.txt.
 * If applicable, add the following below this CDDL HEADER, with the
 * fields enclosed by brackets "[]" replaced with your own identifying
 * information: Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 */

/*
 * Copyright (c) 2008, 2016, Oracle and/or its affiliates. All rights reserved.
 */
package org.opensolaris.opengrok.history;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.text.DateFormat;
import java.text.FieldPosition;
import java.text.ParseException;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.opensolaris.opengrok.configuration.RuntimeEnvironment;
import org.opensolaris.opengrok.logger.LoggerFactory;
import org.opensolaris.opengrok.util.Executor;

/**
 * Access to a Git repository.
 *
 */
public class GitRepository extends Repository {

    private static final Logger LOGGER = LoggerFactory.getLogger(GitRepository.class);

    private static final long serialVersionUID = 1L;
    /**
     * The property name used to obtain the client command for this repository.
     */
    public static final String CMD_PROPERTY_KEY
            = "org.opensolaris.opengrok.history.git";
    /**
     * The command to use to access the repository if none was given explicitly
     */
    public static final String CMD_FALLBACK = "git";

    /**
     * git blame command
     */
    private static final String BLAME = "blame";

    /**
     * arguments to shorten git IDs
     */
    private static final int CSET_LEN = 8;
    private static final String ABBREV_LOG = "--abbrev=" + CSET_LEN;
    private static final String ABBREV_BLAME = "--abbrev=" + (CSET_LEN - 1);

    private static final String[] backupDatePatterns = new String[]{"d MMM yyyy HH:mm:ss Z"};

    /**
     * Pattern used to extract author/revision from git blame.
     */
    private static final Pattern BLAME_PATTERN
            = Pattern.compile("^\\W*(\\w+).+?\\((\\D+).*$");

    public GitRepository() {
        type = "git";
        datePattern = "EE, d MMM yyyy HH:mm:ss Z";
    }

    /**
     * Get path of the requested file given a commit hash. Useful for tracking
     * the path when file has changed its location.
     *
     * @param fileName name of the file to retrieve the path
     * @param revision commit hash to track the path of the file
     * @return full path of the file on success; null string on failure
     */
    private String getCorrectPath(String fileName, String revision) throws IOException {
        List<String> cmd = new ArrayList<>();
        String path = "";

        ensureCommand(CMD_PROPERTY_KEY, CMD_FALLBACK);
        cmd.add(RepoCommand);
        cmd.add(BLAME);
        cmd.add("-c"); // to get correctly formed changeset IDs
        cmd.add(ABBREV_LOG);
        cmd.add("-C");
        cmd.add(fileName);
        File directory = new File(directoryName);
        Executor exec = new Executor(cmd, directory);

        int status = exec.exec();
        if (status != 0) {
            LOGGER.log(Level.SEVERE,
                    "Failed to get blame list in resolving correct path");
            return path;
        }
        try (BufferedReader in = new BufferedReader(exec.getOutputReader())) {
            String pattern = "^\\W*" + revision + " (.+?) .*$";
            Pattern commitPattern = Pattern.compile(pattern);
            String line = "";
            Matcher matcher = commitPattern.matcher(line);
            while ((line = in.readLine()) != null) {
                matcher.reset(line);
                if (matcher.find()) {
                    path = matcher.group(1);
                    break;
                }
            }
        }

        return path;
    }

    /**
     * Get an executor to be used for retrieving the history log for the named
     * file.
     *
     * @param file The file to retrieve history for
     * @param sinceRevision the oldest changeset to return from the executor, or
     *                      {@code null} if all changesets should be returned
     * @return An Executor ready to be started
     */
    Executor getHistoryLogExecutor(final File file, String sinceRevision)
            throws IOException {

        String abs = file.getCanonicalPath();
        String filename = "";
        if (abs.length() > directoryName.length()) {
            filename = abs.substring(directoryName.length() + 1);
        }

        List<String> cmd = new ArrayList<>();
        ensureCommand(CMD_PROPERTY_KEY, CMD_FALLBACK);
        cmd.add(RepoCommand);
        cmd.add("log");
        cmd.add("--abbrev-commit");
        cmd.add(ABBREV_LOG);
        cmd.add("--name-only");
        cmd.add("--pretty=fuller");
        cmd.add("--date=rfc");

        if (RuntimeEnvironment.getInstance().isHandleHistoryOfRenamedFiles()) {
            
            // For plain files we would like to follow the complete history
            // (this is necessary for getting the original name in given revision
            // when handling renamed files)
            if (filename.length() > 0 && !file.isDirectory()) {
                cmd.add("--follow");
                cmd.add("--");
            }
        }

        if (sinceRevision != null) {
            cmd.add(sinceRevision + "..");
        }

        if (filename.length() > 0) {
            cmd.add(filename);
        }

        return new Executor(cmd, new File(getDirectoryName()), sinceRevision != null);
    }

    /**
     * Formatter for rfc 2822 which allows (as rfc) the optional day at the
     * beginning.
     *
     * @see <a href="https://tools.ietf.org/html/rfc2822#page-14" target="_blank">https://tools.ietf.org/html/rfc2822#page-14</a>
     *
     * @return DateFormat which accepts the optional day format
     */
    @Override
    public DateFormat getDateFormat() {
        return new DateFormat() {

            private DateFormat formatter = new SimpleDateFormat(datePattern, Locale.getDefault());
            private DateFormat[] backupFormatters = new DateFormat[backupDatePatterns.length];

            {
                for (int i = 0; i < backupDatePatterns.length; i++) {
                    backupFormatters[i] = new SimpleDateFormat(backupDatePatterns[i], Locale.getDefault());
                }
            }

            @Override
            public StringBuffer format(Date date, StringBuffer toAppendTo, FieldPosition fieldPosition) {
                return formatter.format(date, toAppendTo, fieldPosition);
            }

            @Override
            public Date parse(String source) throws ParseException {
                try {
                    return formatter.parse(source);
                } catch (ParseException ex) {
                    for (int i = 0; i < backupFormatters.length; i++) {
                        try {
                            return backupFormatters[i].parse(source);
                        } catch (ParseException ex1) {
                        }
                    }
                    throw ex;
                }
            }

            @Override
            public Date parse(String source, ParsePosition pos) {
                return formatter.parse(source, pos);
            }
        };
    }

    /**
     * Create a {@code Reader} that reads an {@code InputStream} using the
     * correct character encoding.
     *
     * @param input a stream with the output from a log or blame command
     * @return a reader that reads the input
     * @throws IOException if the reader cannot be created
     */
    Reader newLogReader(InputStream input) throws IOException {
        // Bug #17731: Git always encodes the log output using UTF-8 (unless
        // overridden by i18n.logoutputencoding, but let's assume that hasn't
        // been done for now). Create a reader that uses UTF-8 instead of the
        // platform's default encoding.
        return new InputStreamReader(input, "UTF-8");
    }

    @Override
    public InputStream getHistoryGet(String parent, String basename, String rev) {
        InputStream ret = null;

        File directory = new File(directoryName);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];

        Process process = null;
        try {
            String filename = (new File(parent, basename)).getCanonicalPath()
                    .substring(directoryName.length() + 1);
            ensureCommand(CMD_PROPERTY_KEY, CMD_FALLBACK);
            String argv[] = {RepoCommand, "show", rev + ":" + filename};

            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.directory(directory);
            process = pb.start();
            process.waitFor();

            /*
             * If we failed to get the contents it might be that the file was
             * renamed so we need to find its original name in that revision
             * and retry with the original name.
             */
            if (process.exitValue() != 0) {
                String origpath;
                try {
                    origpath = findOriginalName(filename, rev);
                } catch (IOException exp) {
                    LOGGER.log(Level.SEVERE, "Failed to get original revision: {0}",
                            exp.getClass().toString());
                    return null;
                }
                if (origpath != null) {
                    argv[2] = rev + ":" + origpath;
                    pb = new ProcessBuilder(argv);
                    pb.directory(directory);
                    process = pb.start();
                    process.waitFor();
                }
            }

            InputStream in = process.getInputStream();
            int len;
            boolean error = true;

            while ((len = in.read(buffer)) != -1) {
                error = false;
                if (len > 0) {
                    output.write(buffer, 0, len);
                }
            }
            if (error) {
                process.destroy();
                String path = getCorrectPath(filename, rev);
                argv[2] = rev + ":" + path;
                pb = new ProcessBuilder(argv);
                pb.directory(directory);
                process = pb.start();
                process.waitFor();
                in = process.getInputStream();
                while ((len = in.read(buffer)) != -1) {
                    if (len > 0) {
                        output.write(buffer, 0, len);
                    }
                }
            }

            ret = new ByteArrayInputStream(output.toByteArray());
        } catch (Exception exp) {
            LOGGER.log(Level.SEVERE,
                    "Failed to get history: " + exp.getClass().toString(), exp);
        } finally {
            // Clean up zombie-processes...
            if (process != null) {
                try {
                    process.exitValue();
                } catch (IllegalThreadStateException exp) {
                    // the process is still running??? just kill it..
                    process.destroy();
                }
            }
        }

        return ret;
    }

     /**
     * Get the name of file in given revision
     * @param fullpath file path
     * @param full_rev_to_find revision number (in the form of {rev}:{node|short})
     * @returns original filename
     */
    private String findOriginalName(String fullpath, String full_rev_to_find) 
            throws HistoryException, IOException {
        //String file = fullpath.substring(directoryName.length() + 1);
        String file = fullpath;
        ArrayList<String> argv = new ArrayList<String>();

        // Extract {rev} from the full revision specification string.
        String[] rev_array = full_rev_to_find.split(":");
        String rev_to_find = rev_array[0];
        if (rev_to_find.isEmpty()) {
           LOGGER.log(Level.SEVERE,
                "Invalid revision string: {0}", full_rev_to_find);
            return null;
        }

        /*
         * Get the list of file renames for given file to the specified
         * revision. We need to get them from the newest to the oldest
         * (hence the reverse()) so that we can follow the renames down
         * to the revision we are after.
         * 
         * The command is as follows:
         * git log --follow --pretty="%H" --name-only fullpath
         * 
         * Output format is
         * ==
         * 359f28929b8f9db802efa0199966f2cd2d4879cd
         * 
         * project/src/main/java/com/example/somepackage/Example.java
         * 1c212a3f48a8447fe5784735dbff9c32cd9f7687
         * 
         * project/src/main/java/com/old/example/oldpackage/oldsubpackage/OldExample.java
         * ==
         */
        argv.add(ensureCommand(CMD_PROPERTY_KEY, CMD_FALLBACK));
        argv.add("log");
        argv.add("--follow");
        argv.add("--format=commit:%H" + System.getProperty("line.separator"));
        argv.add("--name-only");
        argv.add(fullpath);

        ProcessBuilder pb = new ProcessBuilder(argv);

        File directory = new File(directoryName);
        pb.directory(directory);
        Process process = null;

        process = pb.start();

        String hash;


        try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()))) {

               String line;
               while ((line = in.readLine()) != null) {
                   if (line.startsWith("commit")) {
                       String parts[] = line.split(":");
                       if (parts.length < 2) {
                           throw new HistoryException("Tag line contains more than 2 columns: " + line);
                       }
                       hash = parts[1];

                       if (hash.startsWith(rev_to_find)) {
                       // we've found our commit - the filename is on the 2nd next line
                       in.readLine();
                       in.readLine();
                       file = in.readLine();
                       break;
                   }
               }
            }
         } finally {
            if (process != null) {
                try {
                    process.exitValue();
                } catch (IllegalThreadStateException e) {
                    // the process is still running??? just kill it..
                    process.destroy();
                }
            }
        }

        return (file);
    }

    /**
     * Annotate the specified file/revision.
     *
     * @param file file to annotate
     * @param revision revision to annotate
     * @return file annotation
     * @throws java.io.IOException
     */
    @Override
    public Annotation annotate(File file, String revision) throws IOException {
        File directory = new File(directoryName);
        List<String> cmd = new ArrayList<>();

        ensureCommand(CMD_PROPERTY_KEY, CMD_FALLBACK);
        cmd.add(RepoCommand);
        cmd.add(BLAME);
        cmd.add("-c"); // to get correctly formed changeset IDs
        cmd.add(ABBREV_BLAME);
        if (revision != null) {
            cmd.add(revision);
        }
        cmd.add(file.getPath());

        Executor exec = new Executor(cmd, directory);
        int status = exec.exec(false);
        // File might have changed its location
        if (status != 0) {
            String origpath;
            try {
                origpath = findOriginalName(file.getPath(), revision);

            } catch (IOException | HistoryException exp) {
                LOGGER.log(Level.SEVERE, "Failed to get original revision: {0}",
                        exp.getClass().toString());
                return null;
            }
            if (origpath != null) {
                cmd.clear();
                ensureCommand(CMD_PROPERTY_KEY, CMD_FALLBACK);
                cmd.add(RepoCommand);
                cmd.add(BLAME);
                cmd.add("-c"); // to get correctly formed changeset IDs
                cmd.add(ABBREV_BLAME);
                if (revision != null) {
                    cmd.add(revision);
                }
                cmd.add("--");
                cmd.add(origpath);

                exec = new Executor(cmd, directory);
                status = exec.exec();
                if (status != 0) {
                    LOGGER.log(Level.SEVERE, "Failed to get blame details for modified file path");
                }

            }

        }
        if (status != 0) {
            LOGGER.log(Level.WARNING,
                    "Failed to get annotations for: \"{0}\" Exit code: {1}",
                    new Object[]{file.getAbsolutePath(), String.valueOf(status)});
        }

        return parseAnnotation(
                newLogReader(exec.getOutputStream()), file.getName());
    }

    protected Annotation parseAnnotation(Reader input, String fileName)
            throws IOException {

        BufferedReader in = new BufferedReader(input);
        Annotation ret = new Annotation(fileName);
        String line = "";
        int lineno = 0;
        Matcher matcher = BLAME_PATTERN.matcher(line);
        while ((line = in.readLine()) != null) {
            ++lineno;
            matcher.reset(line);
            if (matcher.find()) {
                String rev = matcher.group(1);
                String author = matcher.group(2).trim();
                ret.addLine(rev, author, true);
            } else {
                LOGGER.log(Level.SEVERE,
                        "Error: did not find annotation in line {0}: [{1}] of {2}",
                        new Object[]{String.valueOf(lineno), line, fileName});
            }
        }
        return ret;
    }

    @Override
    public boolean fileHasAnnotation(File file) {
        return true;
    }

    @Override
    public void update() throws IOException {
        File directory = new File(getDirectoryName());
        List<String> cmd = new ArrayList<>();
        ensureCommand(CMD_PROPERTY_KEY, CMD_FALLBACK);
        cmd.add(RepoCommand);
        cmd.add("config");
        cmd.add("--list");

        Executor executor = new Executor(cmd, directory);
        if (executor.exec() != 0) {
            throw new IOException(executor.getErrorString());
        }

        if (executor.getOutputString().contains("remote.origin.url=")) {
            cmd.clear();
            cmd.add(RepoCommand);
            cmd.add("pull");
            cmd.add("-n");
            cmd.add("-q");
            if (executor.exec() != 0) {
                throw new IOException(executor.getErrorString());
            }
        }
    }

    @Override
    public boolean fileHasHistory(File file) {
        // Todo: is there a cheap test for whether Git has history
        // available for a file?
        // Otherwise, this is harmless, since Git's commands will just
        // print nothing if there is no history.
        return true;
    }

    @Override
    boolean isRepositoryFor(File file) {
        if (file.isDirectory()) {
            File f = new File(file, ".git");
            return f.exists() && f.isDirectory();
        }
        return false;
    }

    @Override
    public boolean isWorking() {
        if (working == null) {
            ensureCommand(CMD_PROPERTY_KEY, CMD_FALLBACK);
            working = checkCmd(RepoCommand, "--help");
        }
        return working;
    }

    @Override
    boolean hasHistoryForDirectories() {
        return true;
    }

    @Override
    History getHistory(File file) throws HistoryException {
        return getHistory(file, null);
    }

    @Override
    History getHistory(File file, String sinceRevision)
            throws HistoryException {
        RuntimeEnvironment env = RuntimeEnvironment.getInstance();
        History result = new GitHistoryParser().parse(file, this, sinceRevision);
        // Assign tags to changesets they represent
        // We don't need to check if this repository supports tags,
        // because we know it :-)
        if (env.isTagsEnabled()) {
            assignTagsInHistory(result);
        }
        return result;
    }

    @Override
    boolean hasFileBasedTags() {
        return true;
    }

    private TagEntry buildTagEntry(File directory, String tags) throws HistoryException, IOException {
        String hash = null;
        Date date = null;

        ArrayList<String> argv = new ArrayList<>();
        ensureCommand(CMD_PROPERTY_KEY, CMD_FALLBACK);
        argv.add(RepoCommand);
        argv.add("log");
        argv.add("--format=commit:%H" + System.getProperty("line.separator")
                + "Date:%at");
        argv.add("-r");
        argv.add(tags + "^.." + tags);
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.directory(directory);
        Process process;
        process = pb.start();

        try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.startsWith("commit")) {
                    String parts[] = line.split(":");
                    if (parts.length < 2) {
                        throw new HistoryException("Tag line contains more than 2 columns: " + line);
                    }
                    hash = parts[1];
                }
                if (line.startsWith("Date")) {
                    String parts[] = line.split(":");
                    if (parts.length < 2) {
                        throw new HistoryException("Tag line contains more than 2 columns: " + line);
                    }
                    date = new Date((long) (Integer.parseInt(parts[1])) * 1000);
                }
            }
        }

        try {
            process.exitValue();
        } catch (IllegalThreadStateException e) {
            // the process is still running??? just kill it..
            process.destroy();
        }

        // Git can have tags not pointing to any commit, but tree instead
        // Lets use Unix timestamp of 0 for such commits
        if (date == null) {
            date = new Date(0);
        }
        TagEntry result = new GitTagEntry(hash, date, tags);
        return result;
    }

    @Override
    protected void buildTagList(File directory) {
        this.tagList = new TreeSet<>();
        ArrayList<String> argv = new ArrayList<>();
        LinkedList<String> tagsList = new LinkedList<>();
        ensureCommand(CMD_PROPERTY_KEY, CMD_FALLBACK);
        argv.add(RepoCommand);
        argv.add("tag");
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.directory(directory);
        Process process = null;

        try {
            // First we have to obtain list of all tags, and put it asside
            // Otherwise we can't use git to get date & hash for each tag
            process = pb.start();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = in.readLine()) != null) {
                    tagsList.add(line);
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    "Failed to read tag list: {0}", e.getMessage());
            this.tagList = null;
        }

        // Make sure this git instance is not running any more
        if (process != null) {
            try {
                process.exitValue();
            } catch (IllegalThreadStateException e) {
                // the process is still running??? just kill it..
                process.destroy();
            }
        }

        try {
            // Now get hash & date for each tag
            for (String tags : tagsList) {
                TagEntry tagEntry = buildTagEntry(directory, tags);
                // Reverse the order of the list
                this.tagList.add(tagEntry);
            }
        } catch (HistoryException e) {
            LOGGER.log(Level.WARNING,
                    "Failed to parse tag list: {0}", e.getMessage());
            this.tagList = null;
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    "Failed to read tag list: {0}", e.getMessage());
            this.tagList = null;
        }
    }

    @Override
    String determineParent() throws IOException {
        String parent = null;
        File directory = new File(directoryName);

        List<String> cmd = new ArrayList<>();
        ensureCommand(CMD_PROPERTY_KEY, CMD_FALLBACK);
        cmd.add(RepoCommand);
        cmd.add("remote");
        cmd.add("-v");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(directory);
        Process process;
        process = pb.start();

        try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.startsWith("origin") && line.contains("(fetch)")) {
                    String parts[] = line.split("\\s+");
                    if (parts.length != 3) {
                        LOGGER.log(Level.WARNING,
                                "Failed to get parent for {0}", directoryName);
                    }
                    parent = parts[1];
                    break;
                }
            }
        }

        return parent;
    }

    @Override
    String determineBranch() throws IOException {
        String branch = null;
        File directory = new File(directoryName);

        List<String> cmd = new ArrayList<>();
        ensureCommand(CMD_PROPERTY_KEY, CMD_FALLBACK);
        cmd.add(RepoCommand);
        cmd.add("branch");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(directory);
        Process process;
        process = pb.start();

        try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.startsWith("*")) {
                    branch = line.substring(2).trim();
                    break;
                }
            }
        }

        return branch;
    }
}
