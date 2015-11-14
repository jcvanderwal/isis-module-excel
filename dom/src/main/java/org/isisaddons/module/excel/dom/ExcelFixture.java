/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.isisaddons.module.excel.dom;

import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.Resources;

import org.datanucleus.enhancement.Persistable;

import org.apache.isis.applib.DomainObjectContainer;
import org.apache.isis.applib.annotation.MemberOrder;
import org.apache.isis.applib.annotation.Programmatic;
import org.apache.isis.applib.fixturescripts.FixtureResultList;
import org.apache.isis.applib.fixturescripts.FixtureScript;
import org.apache.isis.applib.fixturescripts.FixtureScripts;
import org.apache.isis.applib.services.bookmark.BookmarkService;
import org.apache.isis.applib.value.Blob;

import org.isisaddons.module.excel.dom.util.ExcelServiceImpl;

import lombok.Getter;
import lombok.Setter;

/**
 * This class should be executed using {@link FixtureScripts.MultipleExecutionStrategy#EXECUTE_ONCE_BY_VALUE} (it
 * has value semantics).
 */
public class ExcelFixture extends FixtureScript {

    private final List<Class> classes;

    public ExcelFixture(final URL excelResource, final Class... classes) {
        this(excelResource, Arrays.asList(classes));
    }

    public ExcelFixture(final URL excelResource, final List<Class> classes) {
        this(classes);
        setExcelResource(excelResource);
    }

    public ExcelFixture(final Blob upload, final Class... classes) {
        this(upload, Arrays.asList(classes));
    }

    public ExcelFixture(final Blob blob, final List<Class> classes) {
        this(classes);
        setBlob(blob);
    }

    private ExcelFixture(final List<Class> classes) {
        for (Class cls : classes) {
            final boolean viewModel = ExcelFixtureRowHandler.class.isAssignableFrom(cls);
            final boolean persistable = Persistable.class.isAssignableFrom(cls);
            if (!viewModel && !persistable) {
                throw new IllegalArgumentException(String.format(
                        "Class '%s' does not implement '%s', nor is it persistable",
                        cls.getSimpleName(), ExcelFixtureRowHandler.class.getSimpleName()));
            }
        }
        this.classes = classes;
    }

    /**
     * Input, optional: defines the name of the resource, used as a suffix to override {@link #getQualifiedName()}
     * (disambiguate items when added to {@link FixtureResultList} if multiple instances of {@link ExcelFixture} are
     * used with different excel spreadsheets).
     */
    @Getter @Setter
    @MemberOrder(sequence = "1.1")
    private String excelResourceName;

    /**
     * Input, mandatory ... the Excel spreadsheet to read.
     */
    @Getter @Setter
    @MemberOrder(sequence = "1.2")
    private URL excelResource;

    /**
     * Input, mandatory ... the Excel spreadsheet to read.
     */
    @Getter @Setter
    private Blob blob;

    /**
     * Output: the objects created by this fixture, for a specific persistable/row handler class.
     */
    @Getter
    private final Map<Class, List<Object>> objectsByClass = Maps.newHashMap();

    /**
     * Output: all the objects created by this fixture.
     */
    @Getter
    private final List objects = Lists.newArrayList();

    @Programmatic
    @Override
    public String getQualifiedName() {
        return super.getQualifiedName() + (getExcelResourceName() != null ? "-" + getExcelResourceName() : "");
    }

    @Override
    protected void execute(final ExecutionContext ec) {

        final ExcelServiceImpl excelServiceImpl = new ExcelServiceImpl(container, bookmarkService);

        if (blob == null){
            byte[] bytes = getBytes();
            blob = new Blob("unused", ExcelService.XSLX_MIME_TYPE, bytes);
        }

        for (Class cls : classes) {
            final List rowObjects = excelServiceImpl.fromExcel(
                    blob, cls, ExcelServiceImpl.SheetLookupPolicy.BY_NAME);
            Object previousRow = null;
            for (final Object rowObj : rowObjects) {
                final List<Object> createdObjects = create(rowObj, ec, previousRow);
                if (createdObjects != null) {
                    addToMap(cls, createdObjects);
                    addToCombined(createdObjects);
                }
                previousRow = rowObj;
            }
        }
    }
    //region > bytes
    private byte[] bytes;

    private byte[] getBytes() {
        if (bytes == null) {
            if (blob != null){
                bytes = blob.getBytes();
            } else {
                bytes = readBytes();
            }
        }
        return bytes;
    }

    private byte[] readBytes() {

        final URL excelResource = getExcelResource();
        try {
            bytes = Resources.toByteArray(excelResource);
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read from resource: " + excelResource);
        }
        return bytes;
    }
    //endregion

    private List<Object> create(
            final Object rowObj,
            final ExecutionContext ec,
            final Object previousRow) {
        if (rowObj instanceof ExcelFixtureRowHandler) {
            final ExcelFixtureRowHandler rowHandler = (ExcelFixtureRowHandler) rowObj;
            return rowHandler.handleRow(ec, this, previousRow);
        } else {
            container.persistIfNotAlready(rowObj);
            ec.addResult(this, rowObj);
            return Collections.singletonList(rowObj);
        }
    }

    private void addToMap(final Class cls, final List<Object> createdObjects) {
        List<Object> objectList = objectsByClass.get(cls);
        if (objectList == null) {
            objectList = Lists.newArrayList();
            this.objectsByClass.put(cls, objectList);
        }
        objectList.addAll(createdObjects);
    }

    private void addToCombined(final List<Object> createdObjects) {
        this.objects.addAll(createdObjects);
    }

    //region > hashCode, equals

    @Override
    public boolean equals(final Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;

        final ExcelFixture that = (ExcelFixture) o;

        return Arrays.equals(getBytes(), that.getBytes());

    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(getBytes());
    }

    //endregion

    @javax.inject.Inject
    private DomainObjectContainer container;
    @javax.inject.Inject
    private BookmarkService bookmarkService;

}
