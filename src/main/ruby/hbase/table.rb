#
# Copyright 2010 The Apache Software Foundation
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

include Java

java_import org.apache.hadoop.hbase.client.HTable

java_import org.apache.hadoop.hbase.KeyValue
java_import org.apache.hadoop.hbase.util.Bytes
java_import org.apache.hadoop.hbase.util.Writables

java_import org.apache.hadoop.hbase.client.Put
java_import org.apache.hadoop.hbase.client.Get
java_import org.apache.hadoop.hbase.client.Delete

java_import org.apache.hadoop.hbase.client.Scan
java_import org.apache.hadoop.hbase.filter.FirstKeyOnlyFilter
java_import org.apache.hadoop.hbase.filter.ParseFilter

# Wrapper for org.apache.hadoop.hbase.client.HTable

module Hbase
  class Table
    include HBaseConstants

    def initialize(configuration, table_name, formatter)
      @table = HTable.new(configuration, table_name)
    end
    
    #----------------------------------------------------------------------------------------------
    # Set profiling on or off
    def set_profiling(prof)
      @table.setProfiling(prof)
    end

    #----------------------------------------------------------------------------------------------
    # Get profiling data
    def get_profiling()
      data = @table.getProfilingData()
      if data == nil
        return nil
      else
        return data.toPrettyString()
      end
    end
    #----------------------------------------------------------------------------------------------
    # Put a cell 'value' at specified table/row/column
    def put(row, column, value, timestamp = nil)
      p = Put.new(row.to_s.to_java_bytes)
      family, qualifier = parse_column_name(column)
      if timestamp
        p.add(family, qualifier, timestamp, value.to_s.to_java_bytes)
      else
        p.add(family, qualifier, value.to_s.to_java_bytes)
      end
      @table.put(p)
    end

    #----------------------------------------------------------------------------------------------
    # Delete a cell
    def delete(row, column, timestamp = HConstants::LATEST_TIMESTAMP)
      deleteall(row, column, timestamp)
    end

    #----------------------------------------------------------------------------------------------
    # Delete a row
    def deleteall(row, column = nil, timestamp = HConstants::LATEST_TIMESTAMP)
      d = Delete.new(row.to_s.to_java_bytes, timestamp, nil)
      if column
        family, qualifier = parse_column_name(column)
        d.deleteColumns(family, qualifier, timestamp)
      end
      @table.delete(d)
    end

    #----------------------------------------------------------------------------------------------
    # Increment a counter atomically
    def incr(row, column, value = nil)
      value ||= 1
      family, qualifier = parse_column_name(column)
      @table.incrementColumnValue(row.to_s.to_java_bytes, family, qualifier, value)
    end

    #----------------------------------------------------------------------------------------------
    # Count rows in a table
    def count(interval = 1000, caching_rows = 10)
      # We can safely set scanner caching with the first key only filter
      scan = Scan.new
      scan.cache_blocks = false
      scan.caching = caching_rows
      scan.setFilter(FirstKeyOnlyFilter.new)

      # Run the scanner
      scanner = @table.getScanner(scan)
      count = 0
      iter = scanner.iterator

      # Iterate results
      while iter.hasNext
        row = iter.next
        count += 1
        next unless (block_given? && count % interval == 0)
        # Allow command modules to visualize counting process
        yield(count, String.from_java_bytes(row.getRow))
      end

      # Return the counter
      return count
    end

    #----------------------------------------------------------------------------------------------
    # Get from table
    def get(row, *args)
      get = Get.new(row.to_s.to_java_bytes)
      maxlength = -1

      # Normalize args
      args = args.first if args.first.kind_of?(Hash)
      if args.kind_of?(String) || args.kind_of?(Array)
        columns = [ args ].flatten.compact
        args = { COLUMNS => columns }
      end

      #
      # Parse arguments
      #
      unless args.kind_of?(Hash)
        raise ArgumentError, "Failed parse of of #{args.inspect}, #{args.class}"
      end

      # Get maxlength parameter if passed
      maxlength = args.delete(MAXLENGTH) if args[MAXLENGTH]

      unless args.empty?
        columns = args[COLUMN] || args[COLUMNS]
        if columns
          # Normalize types, convert string to an array of strings
          columns = [ columns ] if columns.is_a?(String)

          # At this point it is either an array or some unsupported stuff
          unless columns.kind_of?(Array)
            raise ArgumentError, "Failed parse column argument type #{args.inspect}, #{args.class}"
          end

          # Get each column name and add it to the filter
          columns.each do |column|
            family, qualifier = parse_column_name(column.to_s)
            if qualifier
              get.addColumn(family, qualifier)
            else
              get.addFamily(family)
            end
          end

          # Additional params
          get.setMaxVersions(args[VERSIONS] || 1)
          get.setTimeStamp(args[TIMESTAMP]) if args[TIMESTAMP]
        else
          # May have passed TIMESTAMP and row only; wants all columns from ts.
          unless ts = args[TIMESTAMP]
            raise ArgumentError, "Failed parse of #{args.inspect}, #{args.class}"
          end

          # Set the timestamp
          get.setTimeStamp(ts.to_i)
        end
      end

      # Call hbase for the results
      result = @table.get(get)
      return nil if result.isEmpty

      # Print out results.  Result can be Cell or RowResult.
      res = {}
      result.list.each do |kv|
        family = String.from_java_bytes(kv.getFamily)
        qualifier = Bytes::toStringBinary(kv.getQualifier)

        column = "#{family}:#{qualifier}"
        value = to_string(column, kv, maxlength)

        if block_given?
          yield(column, value)
        else
          res[column] = value
        end
      end

      # If block given, we've yielded all the results, otherwise just return them
      return ((block_given?) ? nil : res)
    end

    #----------------------------------------------------------------------------------------------
    # Fetches and decodes a counter value from hbase
    def get_counter(row, column)
      family, qualifier = parse_column_name(column.to_s)
      # Format get request
      get = Get.new(row.to_s.to_java_bytes)
      get.addColumn(family, qualifier)
      get.setMaxVersions(1)

      # Call hbase
      result = @table.get(get)
      return nil if result.isEmpty

      # Fetch cell value
      cell = result.list.first
      Bytes::toLong(cell.getValue)
    end

    #----------------------------------------------------------------------------------------------
    # Scans whole table or a range of keys and returns rows matching specific criterias
    def scan(args = {})
      unless args.kind_of?(Hash)
        raise ArgumentError, "Arguments should be a hash. Failed to parse #{args.inspect}, #{args.class}"
      end

      limit = args.delete("LIMIT") || -1
      maxlength = args.delete("MAXLENGTH") || -1

      if args.any?
        filter = args["FILTER"]
        startrow = args["STARTROW"] || ''
        stoprow = args["STOPROW"]
        timestamp = args["TIMESTAMP"]
        columns = args["COLUMNS"] || args["COLUMN"] || get_all_columns
        cache = args["CACHE_BLOCKS"] || true
        versions = args["VERSIONS"] || 1

        # Normalize column names
        columns = [columns] if columns.class == String
        unless columns.kind_of?(Array)
          raise ArgumentError.new("COLUMNS must be specified as a String or an Array")
        end

        scan = if stoprow
          Scan.new(startrow.to_java_bytes, stoprow.to_java_bytes)
        else
          Scan.new(startrow.to_java_bytes)
        end

        columns.each { |c| scan.addColumns(c) }

        unless filter.class == String
          scan.setFilter(filter)
        else
          scan.setFilter(ParseFilter.new.parseFilterString(filter))
        end

        scan.setTimeStamp(timestamp) if timestamp
        scan.setCacheBlocks(cache)
        scan.setMaxVersions(versions) if versions > 1
      else
        scan = Scan.new
      end

      # Start the scanner
      scanner = @table.getScanner(scan)
      count = 0
      res = {}
      iter = scanner.iterator

      # Iterate results
      while iter.hasNext
        if limit > 0 && count >= limit
          break
        end

        row = iter.next
        key = Bytes::toStringBinary(row.getRow)

        row.list.each do |kv|
          family = String.from_java_bytes(kv.getFamily)
          qualifier = Bytes::toStringBinary(kv.getQualifier)

          column = "#{family}:#{qualifier}"
          cell = to_string(column, kv, maxlength)

          if block_given?
            yield(key, "column=#{column}, #{cell}")
          else
            res[key] ||= {}
            res[key][column] = cell
          end
        end

        # One more row processed
        count += 1
      end

      return ((block_given?) ? count : res)
    end

    #----------------------------------------------------------------------------------------
    # Helper methods

    # Returns a list of column names in the table
    def get_all_columns
      @table.table_descriptor.getFamilies.map do |family|
        "#{family.getNameAsString}:"
      end
    end

    # Checks if current table is one of the 'meta' tables
    def is_meta_table?
      tn = @table.table_name
      Bytes.equals(tn, HConstants::META_TABLE_NAME) || Bytes.equals(tn, HConstants::ROOT_TABLE_NAME)
    end

    # Returns family and (when has it) qualifier for a column name
    def parse_column_name(column)
      split = KeyValue.parseColumn(column.to_java_bytes)
      return split[0], (split.length > 1) ? split[1] : nil
    end

    # Make a String of the passed kv
    # Intercept cells whose format we know such as the info:regioninfo in .META.
    def to_string(column, kv, maxlength = -1)
      if is_meta_table?
        if column == 'info:regioninfo'
          hri = Writables.getHRegionInfoOrNull(kv.getValue)
          return "timestamp=%d, value=%s" % [kv.getTimestamp, hri.toString]
        end
        if column == 'info:serverstartcode'
          if kv.getValue.length > 0
            str_val = Bytes.toLong(kv.getValue)
          else
            str_val = Bytes.toStringBinary(kv.getValue)
          end
          return "timestamp=%d, value=%s" % [kv.getTimestamp, str_val]
        end
      end

      val = "timestamp=#{kv.getTimestamp}, value=#{Bytes::toStringBinary(kv.getValue)}"
      (maxlength != -1) ? val[0, maxlength] : val
    end

  end
end
