/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sis.gui.coverage;

import java.util.Optional;
import javafx.util.Callback;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableView;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableColumn.CellDataFeatures;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Pos;
import org.apache.sis.measure.NumberRange;
import org.apache.sis.coverage.Category;
import org.apache.sis.coverage.SampleDimension;
import org.apache.sis.util.resources.Vocabulary;
import org.apache.sis.internal.gui.Styles;


/**
 * Factory methods for cell values to shown in the sample dimension or category table.
 *
 * <p>Current implementation handles only {@link SampleDimension} instances, but it
 * can be extended for handling also {@link Category} objects in a future version.
 * The class name is in anticipation for such extension.</p>
 *
 * @author  Martin Desruisseaux (Geomatys)
 * @version 1.1
 * @since   1.1
 * @module
 */
final class CategoryCellFactory implements Callback<TableColumn<SampleDimension,Number>, TableCell<SampleDimension,Number>> {
    /**
     * Identifier of columns shown in the sample dimension or category table.
     */
    private static final String NAME = "name", MINIMUM = "minimum", MAXIMUM = "maximum", UNITS = "units";

    /**
     * The format to use for formatting minimum and maximum values.
     */
    private final CellFormat cellFormat;

    /**
     * Creates a cell value factory.
     */
    CategoryCellFactory(final CellFormat format) {
        cellFormat = format;
    }

    /**
     * Creates a table of sample dimensions.
     *
     * @param  vocabulary  resources for the locale in use.
     */
    TableView<SampleDimension> createSampleDimensionTable(final Vocabulary vocabulary) {
        final TableView<SampleDimension> table = new TableView<>();
        table.setPrefHeight(5 * Styles.ROW_HEIGHT);                         // Show approximately 5 rows.
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.getColumns().setAll(
                createStringColumn(vocabulary, Vocabulary.Keys.Name,    NAME),
                createNumberColumn(vocabulary, Vocabulary.Keys.Minimum, MINIMUM),
                createNumberColumn(vocabulary, Vocabulary.Keys.Maximum, MAXIMUM),
                createStringColumn(vocabulary, Vocabulary.Keys.Units,   UNITS));
        return table;
    }

    /**
     * Creates a new column with a title identified by the given key.
     */
    private TableColumn<SampleDimension,String> createStringColumn(final Vocabulary vocabulary, final short key, final String id) {
        final TableColumn<SampleDimension,String> column = new TableColumn<>(vocabulary.getString(key));
        column.setCellValueFactory(CategoryCellFactory::getStringValue);
        column.setId(id);
        return column;
    }

    /**
     * Creates a new column with a title identified by the given key.
     */
    private TableColumn<SampleDimension,Number> createNumberColumn(final Vocabulary vocabulary, final short key, final String id) {
        final TableColumn<SampleDimension,Number> column = new TableColumn<>(vocabulary.getString(key));
        column.setCellValueFactory(this::getNumberValue);
        column.setCellFactory(this);
        column.setId(id);
        return column;
    }

    /**
     * Creates a cell for numeric values.
     * This method is public as an implementation side-effect; do not rely on that.
     */
    @Override
    public TableCell<SampleDimension,Number> call(final TableColumn<SampleDimension,Number> column) {
        return new Numeric(cellFormat);
    }

    /**
     * Cell for numeric values.
     */
    private static final class Numeric extends TableCell<SampleDimension,Number> {
        /** The format to use for formatting minimum and maximum values. */
        private final CellFormat cellFormat;

        /** Creates a new numeric cell. */
        Numeric(final CellFormat format) {
            cellFormat = format;
            setAlignment(Pos.CENTER_RIGHT);
        }

        /** Invoked when a new numeric value is set in this cell. */
        @Override public void updateItem(final Number item, final boolean empty) {
            setText(empty || item == null ? "" : cellFormat.format(item));
        }
    }

    /**
     * Invoked when the table needs to render a textual cell in the sample dimension table.
     */
    private static ObservableValue<String> getStringValue(final CellDataFeatures<SampleDimension,String> cell) {
        final Optional<?> text;
        final SampleDimension sd = cell.getValue();
        switch (cell.getTableColumn().getId()) {
            case NAME:  text = Optional.ofNullable(sd.getName()); break;
            case UNITS: text = sd.getUnits(); break;
            default: throw new AssertionError();       // Should not happen.
        }
        return text.map(Object::toString).map(ReadOnlyObjectWrapper::new).orElse(null);
    }

    /**
     * Invoked when the table need to render a numeric cell in the sample dimension table.
     */
    private ObservableValue<Number> getNumberValue(final CellDataFeatures<SampleDimension,Number> cell) {
        final Optional<Number> value;
        final Optional<NumberRange<?>> range = cell.getValue().getSampleRange();
        switch (cell.getTableColumn().getId()) {
            case MINIMUM: value = range.map(NumberRange::getMinValue); break;
            case MAXIMUM: value = range.map(NumberRange::getMaxValue); break;
            default: throw new AssertionError();       // Should not happen.
        }
        return value.map(ReadOnlyObjectWrapper::new).orElse(null);
    }
}