/*-
 * ========================LICENSE_START=================================
 * smooks-routing-cartridge
 * %%
 * Copyright (C) 2020 Smooks
 * %%
 * Licensed under the terms of the Apache License Version 2.0, or
 * the GNU Lesser General Public License version 3.0 or later.
 * 
 * SPDX-License-Identifier: Apache-2.0 OR LGPL-3.0-or-later
 * 
 * ======================================================================
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 * ======================================================================
 * 
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 * =========================LICENSE_END==================================
 */
package org.smooks.cartridges.routing.file;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.smooks.api.ExecutionContext;
import org.smooks.api.SmooksConfigException;
import org.smooks.api.SmooksException;
import org.smooks.api.TypedKey;
import org.smooks.api.expression.ExpressionEvaluator;
import org.smooks.assertion.AssertArgument;
import org.smooks.cartridges.routing.SmooksRoutingException;
import org.smooks.engine.expression.MVELExpressionEvaluator;
import org.smooks.io.AbstractOutputStreamResource;
import org.smooks.support.DollarBraceDecoder;
import org.smooks.support.FreeMarkerTemplate;
import org.smooks.support.FreeMarkerUtils;

import jakarta.annotation.PostConstruct;
import javax.inject.Inject;
import java.io.*;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An {@link AbstractOutputStreamResource} implementation
 * that handles file output streams.
 * <p/>
 * <p>
 * Example configuration:
 * <pre>
 * &lt;resource-config selector="order-item"&gt;
 *    &lt;resource&gt;org.smooks.io.file.FileOutputStreamResource&lt;/resource&gt;
 *    &lt;param name="resourceName"&gt;resourceName&lt;/param&gt;
 *    &lt;param name="fileNamePattern"&gt;orderitem-${order.orderId}-${order.orderItem.itemId}.xml&lt;/param&gt;
 *    &lt;param name="destinationDirectoryPattern"&gt;order-${order.orderId}&lt;/param&gt;
 *    &lt;param name="listFileNamePattern"&gt;orderitems-${order.orderId}.lst&lt;/param&gt;
 * &lt;/resource-config&gt;
 *
 * Optional properties (default values shown):
 *    &lt;param name="highWaterMark"&gt;200&lt;/param&gt;
 *    &lt;param name="highWaterMarkTimeout"&gt;60000&lt;/param&gt;
 * </pre>
 * <p>
 * Description of configuration properties:
 * <ul>
 * <li><i>resourceName</i>: the name of this resouce. Will be used to identify this resource.
 * <li><i>fileNamePattern</i>: is the pattern that will be used to generate file names. The file is
 * created in the destinationDirectory.  Supports templating.
 * <li><i>listFileNamePattern</i>: is name of the file that will contain the file names generated by this
 * configuration. The file is created in the destinationDirectory.  Supports templating.
 * <li><i>destinationDirectoryPattern</i>: is the destination directory for files created by this router.   Supports templating.
 * <li><i>highWaterMark</i>: max number of output files in the destination directory at any time.
 * <li><i>highWaterMarkTimeout</i>: number of ms to wait for the system to process files in the destination
 * directory so that the number of files drops below the highWaterMark.
 * <li><i>highWaterMarkPollFrequency</i>: number of ms to wait between checks on the High Water Mark, while
 * waiting for it to drop.
 * <li><i>closeOnCondition</i>: An MVEL expression. If it returns true then the output stream is closed on the visitAfter event
 * else it is kept open. If the expression is not set then output stream is closed by default.
 * <li><i>append</i>: Will append to the file specified with the 'fileNamePattern' property. This is useful
 * for example when you want to append to a single csv file.
 * </ul>
 * <p>
 * <b>When does a new file get created?</b><br>
 * As soon as an object tries to retrieve the Writer or the OutputStream from this OutputStreamResource and
 * the Stream isn't open then a new file is created. Using the 'closeOnCondition' property you can control
 * when a stream get closed. As long as the stream isn't closed, the same file is used to write too. At then
 * end of the filter process the stream always gets closed. Nothing stays open.
 *
 * @author <a href="mailto:daniel.bevenius@gmail.com">Daniel Bevenius</a>
 * @author <a href="mailto:maurice.zeijen@smies.com">maurice.zeijen@smies.com</a>
 */
public class FileOutputStreamResource extends AbstractOutputStreamResource {
    private static final String TMP_FILE_CONTEXT_KEY_PREFIX = FileOutputStreamResource.class.getName() + "#tmpFile:";

    private static final String LINE_SEPARATOR = System.getProperty("line.separator");
    private static final Object LOCK = new Object();

    private static final Logger LOGGER = LoggerFactory.getLogger(FileOutputStreamResource.class);

    @Inject
    private String fileNamePattern;
    private FreeMarkerTemplate fileNameTemplate;

    @Inject
    private String destinationDirectoryPattern;
    private FreeMarkerTemplate destinationDirectoryTemplate;
    private FileFilter fileFilter;

    @Inject
    private Optional<String> listFileNamePattern;
    private FreeMarkerTemplate listFileNameTemplate;

    @Inject
    private Boolean append = false;

    private String listFileNamePatternCtxKey;

    @Inject
    private Integer highWaterMark = 200;
    @Inject
    private Long highWaterMarkTimeout = 60000L;
    @Inject
    private Long highWaterMarkPollFrequency = 1000L;

    @Inject
    private Optional<ExpressionEvaluator> closeOnCondition;

    //	public

    public FileOutputStreamResource setFileNamePattern(String fileNamePattern) {
        AssertArgument.isNotNullAndNotEmpty(fileNamePattern, "fileNamePattern");
        this.fileNamePattern = fileNamePattern;
        return this;
    }

    public FileOutputStreamResource setDestinationDirectoryPattern(String destinationDirectoryPattern) {
        AssertArgument.isNotNullAndNotEmpty(destinationDirectoryPattern, "destinationDirectoryPattern");
        this.destinationDirectoryPattern = destinationDirectoryPattern;
        return this;
    }

    public FileOutputStreamResource setListFileNamePattern(String listFileNamePattern) {
        AssertArgument.isNotNullAndNotEmpty(listFileNamePattern, "listFileNamePattern");
        this.listFileNamePattern = Optional.of(listFileNamePattern);
        return this;
    }

    public FileOutputStreamResource setListFileNamePatternCtxKey(String listFileNamePatternCtxKey) {
        AssertArgument.isNotNullAndNotEmpty(listFileNamePatternCtxKey, "listFileNamePatternCtxKey");
        this.listFileNamePatternCtxKey = listFileNamePatternCtxKey;
        return this;
    }

    public FileOutputStreamResource setHighWaterMark(int highWaterMark) {
        this.highWaterMark = highWaterMark;
        return this;
    }

    public FileOutputStreamResource setHighWaterMarkTimeout(long highWaterMarkTimeout) {
        this.highWaterMarkTimeout = highWaterMarkTimeout;
        return this;
    }

    public FileOutputStreamResource setHighWaterMarkPollFrequency(long highWaterMarkPollFrequency) {
        this.highWaterMarkPollFrequency = highWaterMarkPollFrequency;
        return this;
    }

    public void setCloseOnCondition(String closeOnCondition) {
        AssertArgument.isNotNullAndNotEmpty(closeOnCondition, "closeOnCondition");
        this.closeOnCondition = Optional.of(new MVELExpressionEvaluator());
        this.closeOnCondition.get().setExpression(closeOnCondition);
    }

    public FileOutputStreamResource setAppend(boolean append) {
        this.append = append;
        return this;
    }

    @PostConstruct
    public void initialize() throws SmooksConfigException {
        if (fileNamePattern == null) {
            throw new SmooksConfigException("Null 'fileNamePattern' configuration parameter.");
        }
        if (destinationDirectoryPattern == null) {
            throw new SmooksConfigException("Null 'destinationDirectoryPattern' configuration parameter.");
        }

        fileNameTemplate = new FreeMarkerTemplate(fileNamePattern);
        destinationDirectoryTemplate = new FreeMarkerTemplate(destinationDirectoryPattern);

        fileFilter = new SplitFilenameFilter(fileNamePattern);

        if (listFileNamePattern.isPresent()) {
            listFileNameTemplate = new FreeMarkerTemplate(listFileNamePattern.get());
            listFileNamePatternCtxKey = FileOutputStreamResource.class.getName() + "#" + listFileNamePattern;
        }
    }

    @Override
    public FileOutputStream getOutputStream(final ExecutionContext executionContext) throws SmooksRoutingException, IOException {
        Map<String, Object> beanMap = FreeMarkerUtils.getMergedModel(executionContext);
        String destinationDirName = destinationDirectoryTemplate.apply(beanMap);
        File destinationDirectory = new File(destinationDirName);

        assertTargetDirectoryOK(destinationDirectory);
        waitWhileAboveHighWaterMark(destinationDirectory);

        if (append) {
            File outputFile = new File(destinationDirectory, getOutputFileName(executionContext));
            return new FileOutputStream(outputFile, true);
        } else {
            final File tmpFile = File.createTempFile("." + UUID.randomUUID().toString(), ".working", destinationDirectory);
            final FileOutputStream fileOutputStream = new FileOutputStream(tmpFile, false);
            executionContext.put(TypedKey.of(TMP_FILE_CONTEXT_KEY_PREFIX + getResourceName()), tmpFile);
            return fileOutputStream;
        }
    }

    private void assertTargetDirectoryOK(File destinationDirectory) throws SmooksRoutingException {
        if (destinationDirectory.exists() && !destinationDirectory.isDirectory()) {
            throw new SmooksRoutingException("The file routing target directory '" + destinationDirectory.getAbsolutePath() + "' exist but is not a directory. destinationDirectoryPattern: '" + destinationDirectoryPattern + "'");
        }

        if (!destinationDirectory.exists()) {
            synchronized (LOCK) {
                // Allow multiple threads to concurrently attempt creating
                // the output directory. Only one will be able to create the
                // directory due to the lock obtained above.
                if (!destinationDirectory.exists()) {
                    if (!destinationDirectory.mkdirs()
                            && !(destinationDirectory.exists() && destinationDirectory.isDirectory())) {
                        throw new SmooksRoutingException("Failed to create file routing target directory '" + destinationDirectory.getAbsolutePath() + "'. destinationDirectoryPattern: '" + destinationDirectoryPattern + "'");
                    }
                }
            }
        }
    }

    private void waitWhileAboveHighWaterMark(File destinationDirectory) throws SmooksRoutingException {
        if (highWaterMark == -1) {
            return;
        }

        File[] currentList = destinationDirectory.listFiles(fileFilter);
        if (currentList.length >= highWaterMark) {
            long start = System.currentTimeMillis();

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Destination directoy '" + destinationDirectory.getAbsolutePath() + "' contains " + currentList.length + " file matching pattern '" + listFileNamePattern + "'.  High Water Mark is " + highWaterMark + ".  Waiting for file count to drop.");
            }

            while (System.currentTimeMillis() < start + highWaterMarkTimeout) {
                try {
                    Thread.sleep(highWaterMarkPollFrequency);
                } catch (InterruptedException e) {
                    LOGGER.error("Interrupted", e);
                    return;
                }
                currentList = destinationDirectory.listFiles(fileFilter);
                if (currentList.length < highWaterMark) {
                    return;
                }
            }

            throw new SmooksRoutingException("Failed to route message to Filesystem destination '" + destinationDirectory.getAbsolutePath() + "'. Timed out (" + highWaterMarkTimeout + " ms) waiting for the number of '" + listFileNamePattern + "' files to drop below High Water Mark (" + highWaterMark + ").  Consider increasing 'highWaterMark' and/or 'highWaterMarkTimeout' param values.");
        }
    }

    /* (non-Javadoc)
     * @see org.smooks.io.AbstractOutputStreamResource#closeCondition(org.smooks.container.ExecutionContext)
     */
    @Override
    protected boolean closeCondition(ExecutionContext executionContext) {
        return closeOnCondition.map(expressionEvaluator -> expressionEvaluator.eval(executionContext.getBeanContext().getBeanMap())).orElse(true);
    }

    @Override
    protected void closeResource(ExecutionContext executionContext) {
        try {
            super.closeResource(executionContext);
        } finally {
            if (!append) {
                File newFile = renameWorkingFile(executionContext);
                if (newFile != null) {
                    addToListFile(executionContext, newFile);
                }
            }
        }
    }

    //	private

    private File renameWorkingFile(ExecutionContext executionContext) {
        File workingFile = executionContext.get(TypedKey.of(TMP_FILE_CONTEXT_KEY_PREFIX + getResourceName()));

        if (workingFile == null || !workingFile.exists()) {
            return null;
        }

        String newFileName = getOutputFileName(executionContext);

        //	create a new file in the destination directory
        File newFile = new File(workingFile.getParentFile(), newFileName);

        if (newFile.exists()) {
            throw new SmooksException("Could not rename [" + workingFile.getAbsolutePath() + "] to [" + newFile.getAbsolutePath() + "]. [" + newFile.getAbsolutePath() + "] already exists.");
        }

        //	try to rename the tmp file to the new file
        boolean renameTo = workingFile.renameTo(newFile);
        if (!renameTo) {
            throw new SmooksException("Could not rename [" + workingFile.getAbsolutePath() + "] to [" + newFile.getAbsolutePath() + "]");
        }
        workingFile.delete();

        return newFile;
    }

    private String getOutputFileName(ExecutionContext executionContext) {
        Map<String, Object> beanMap = FreeMarkerUtils.getMergedModel(executionContext);
        return fileNameTemplate.apply(beanMap);
    }

    private void addToListFile(ExecutionContext executionContext, File newFile) {
        if (listFileNamePatternCtxKey != null) {
            FileWriter writer = executionContext.get(TypedKey.of(listFileNamePatternCtxKey));

            if (writer == null) {
                String listFileName = getListFileName(executionContext);
                File listFile = new File(newFile.getParentFile(), listFileName);

                FileListAccessor.addFileName(listFile.getAbsolutePath(), executionContext);
                try {
                    writer = new FileWriter(listFile);
                    executionContext.put(TypedKey.of(listFileNamePatternCtxKey), writer);
                } catch (IOException e) {
                    throw new SmooksException("", e);
                }
            }

            try {
                writer.write(newFile.getAbsolutePath() + LINE_SEPARATOR);
                writer.flush();
            } catch (IOException e) {
                throw new SmooksException("IOException while trying to write to list file [" + getListFileName(executionContext) + "] :", e);
            }
        }
    }

    @Override
    public void executeExecutionLifecycleCleanup(ExecutionContext executionContext) {
        super.executeExecutionLifecycleCleanup(executionContext);

        // Close the list file, if there's one open...
        if (listFileNamePatternCtxKey != null) {
            FileWriter writer = executionContext.get(TypedKey.of(listFileNamePatternCtxKey));

            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException e) {
                    LOGGER.debug("Failed to close list file '" + getListFileName(executionContext) + "'.", e);
                }
            }
        }
    }

    private String getListFileName(ExecutionContext executionContext) {
        Map<String, Object> beanMap = FreeMarkerUtils.getMergedModel(executionContext);
        return listFileNameTemplate.apply(beanMap);
    }

    public static class SplitFilenameFilter implements FileFilter {

        private final Pattern regexPattern;

        private SplitFilenameFilter(String filenamePattern) {
            // Convert the filename pattern to a regexp...
            String pattern = DollarBraceDecoder.replaceTokens(filenamePattern, ".*");
            regexPattern = Pattern.compile(pattern);
        }

        public boolean accept(File file) {
            Matcher matcher = regexPattern.matcher(file.getName());
            return matcher.matches();
        }
    }
}
