/*
 * The MIT License
 *
 * Copyright (c) 2010 Marcus Thiesen (marcus@thiesen.org)
 *
 * This file is part of HelenaORM.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.thiesen.helenaorm;

import java.beans.PropertyDescriptor;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import me.prettyprint.cassandra.dao.Command;
import me.prettyprint.cassandra.service.Keyspace;

import org.apache.cassandra.thrift.Column;
import org.apache.cassandra.thrift.ColumnParent;
import org.apache.cassandra.thrift.ColumnPath;
import org.apache.cassandra.thrift.NotFoundException;
import org.apache.cassandra.thrift.SlicePredicate;
import org.apache.cassandra.thrift.SuperColumn;
import org.apache.commons.beanutils.PropertyUtils;
import org.thiesen.helenaorm.annotations.HelenaBean;
import org.thiesen.helenaorm.annotations.KeyProperty;
import org.thiesen.helenaorm.annotations.SuperColumnProperty;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.ImmutableSet.Builder;
import org.apache.cassandra.thrift.SliceRange;


/**
 *
 * @author richard wooding & the rest of the crowd. 
 * @param <T>
 */
public class HelenaDAO<T> {

    private final String _hostname;
    private final int _port;
    private final String _keyspace;
    private final String _columnFamily;
    private final PropertyDescriptor[] _propertyDescriptors;
    private final ImmutableList<byte[]> _columnNames;
    private final Class<T> _clz;
    private PropertyDescriptor _keyPropertyDescriptor;
    private PropertyDescriptor _superColumnPropertyDescriptor;
    private final TypeConverter _typeConverter;

    HelenaDAO( final Class<T> clz, final String hostname, final int port, final SerializeUnknownClasses serializationPolicy,
            final ImmutableMap<Class<?>, TypeMapping<?>> typeMappings ) {
        if ( !clz.isAnnotationPresent( HelenaBean.class ) ) {
            throw new IllegalArgumentException("Trying to get a HelenaDAO for a class that is not mapped with @HelenaBean");
        }
        final HelenaBean annotation = clz.getAnnotation( HelenaBean.class );
        _typeConverter = new TypeConverter( typeMappings, serializationPolicy );
        _clz = clz;
        _propertyDescriptors = PropertyUtils.getPropertyDescriptors( clz );
        _columnFamily = annotation.columnFamily();
        _hostname = hostname;
        _port = port;
        _keyspace = annotation.keyspace();

        final Builder<byte[]> setBuilder = ImmutableSet.<byte[]>builder();
        for ( final PropertyDescriptor descriptor : _propertyDescriptors ) {
            setBuilder.add( _typeConverter.stringToBytes( descriptor.getName() ) );
            if ( isKeyProperty( descriptor  ) ) {
                _keyPropertyDescriptor = descriptor;
            }
            if ( isSuperColumnProperty( descriptor ) ) {
                _superColumnPropertyDescriptor = descriptor;
            }
        }
        _columnNames = ImmutableList.copyOf( setBuilder.build() );

        if ( _keyPropertyDescriptor == null ) {
            throw new HelenaRuntimeException("Could not find key of class " + clz.getName() + ", did you annotate with @KeyProperty" );
        }
    }

    private boolean isSuperColumnProperty( final PropertyDescriptor descriptor ) {
        return safeIsAnnotationPresent( descriptor, SuperColumnProperty.class );
    }

    /**
     * Inserts a object into Cassandra
     * @param object The object to insert.
     */
    public void insert( final T object ) {

        final MarshalledObject marshalledObject = MarshalledObject.create();

        for ( final PropertyDescriptor d : _propertyDescriptors ) {
            if ( isReadWrite( d ) ) {
                try {
                    final String name = d.getName();
                    final byte[] value = _typeConverter.convertValueObjectToByteArray( PropertyUtils.getProperty( object, name ) );
                    if ( isKeyProperty( d ) ) {
                        marshalledObject.setKey( value );
                    } if ( isSuperColumnProperty( d ) ) {
                        marshalledObject.setSuperColumn( value );
                    } else {
                        marshalledObject.addValue( name, value );
                    }

                } catch ( final NoSuchMethodException e ) {
                    throw new HelenaRuntimeException( e );
                } catch ( final IllegalAccessException e ) {
                    throw new HelenaRuntimeException( e );
                } catch ( final InvocationTargetException e ) {
                    throw new HelenaRuntimeException( e );
                }
            }
        }

        if ( marshalledObject.getKey() == null ||
                marshalledObject.getKey().length == 0 ) {
            throw new HelenaRuntimeException("Key is null, can't store object");
        }


        store( marshalledObject );

    }

    private boolean isKeyProperty( final PropertyDescriptor d ) {
        return safeIsAnnotationPresent( d, KeyProperty.class );
    }

    private boolean safeIsAnnotationPresent( final PropertyDescriptor d, final Class<? extends Annotation> annotation ) {
        return nullSafeAnnotationPresent( annotation, d.getReadMethod() ) ||
        nullSafeAnnotationPresent( annotation, d.getWriteMethod() );
    }

    private boolean nullSafeAnnotationPresent( final Class<? extends Annotation> annotation, final Method method ) {
        if ( method != null ) {
            if ( method.isAnnotationPresent( annotation ) ) {
                return true;
            }
        }
        return false;
    }

    private boolean isReadWrite( final PropertyDescriptor d ) {
        return d.getReadMethod() != null && d.getWriteMethod() != null;
    }

    private void store( final MarshalledObject marshalledObject ) {
        final byte[] idColumn = marshalledObject.getKey();
        final List<Column> columnList = Lists.newLinkedList();
        final long timestamp = System.currentTimeMillis();
        for ( final Map.Entry<String, byte[]> property : marshalledObject.getEntries() ) {
            columnList.add( toColumn( property, timestamp ) );
        }

        final Map<String, List<Column>> columnMap;
        final Map<String, List<SuperColumn>> superColumnMap;

        if ( marshalledObject.isSuperColumnPresent() ) {
            final SuperColumn superColumn = new SuperColumn( marshalledObject.getSuperColumn(), columnList );
            superColumnMap = ImmutableMap.<String, List<SuperColumn>>of( _columnFamily, ImmutableList.of( superColumn ) );
            columnMap = null;
        } else {
            columnMap = ImmutableMap.<String,List<Column>>of( _columnFamily, columnList );
            superColumnMap = null;
        }

        try {
            execute(new Command<Void>(){
                @Override
                public Void execute(final Keyspace ks) throws Exception {
                    ks.batchInsert( _typeConverter.bytesToString( idColumn ), 
                            columnMap, superColumnMap );

                    return null;
                }
            } );
        } catch ( final Exception e ) {
            throw new HelenaRuntimeException(e);
        }

    }

    private Column toColumn( final Entry<String, byte[]> property, final long timestamp ) {
        return new Column( _typeConverter.stringToBytes( property.getKey() ), property.getValue(), timestamp );
    }

    private <V> V execute(final Command<V> command) throws Exception {
        return command.execute(_hostname, _port, _keyspace);
    }

    public T get(final String key) {
        final ColumnParent parent = makeColumnParent();
        final SlicePredicate predicate = makeSlicePredicateWithAllPropertyColumns();

        try {
            return execute(new Command<T>(){
                @Override
                public T execute(final Keyspace ks) throws Exception {
                    try {
                        final List<Column> slice = ks.getSlice( key, parent , predicate );


                        return applyColumns( key, slice );
                    } catch (final NotFoundException e) {
                        return null;
                    }
                }
            }); 
        } catch ( final Exception e ) {
            throw new HelenaRuntimeException( e );
        }
    }

    private T applyColumns( final String key, final Iterable<Column> slice ) {
        try {
            final T newInstance = _clz.newInstance();

            PropertyUtils.setProperty( newInstance, _keyPropertyDescriptor.getName(),
                    _typeConverter.convertByteArrayToValueObject( _keyPropertyDescriptor.getReadMethod().getReturnType(),
                            _typeConverter.stringToBytes( key ) ) );

            for ( final Column c : slice ) {
                final String name = _typeConverter.bytesToString( c.name );
                if ( PropertyUtils.isWriteable( newInstance, name ) ) {
                    final PropertyDescriptor propertyDescriptor = PropertyUtils.getPropertyDescriptor( newInstance, name );
                    final Class<?> returnType = propertyDescriptor.getReadMethod().getReturnType();
                    PropertyUtils.setProperty( newInstance, name, _typeConverter.convertByteArrayToValueObject( returnType, c.value ) );
                }
            }

            return newInstance;

        } catch ( final InstantiationException e ) {
            throw new HelenaRuntimeException("Could not instanciate " + _clz.getName(), e );
        } catch ( final IllegalAccessException e ) {
            throw new HelenaRuntimeException("Could not instanciate " + _clz.getName(), e );
        } catch ( final InvocationTargetException e ) {
            throw new HelenaRuntimeException( e );
        } catch ( final NoSuchMethodException e ) {
            throw new HelenaRuntimeException( e );
        }
    }

    private List<T> applyColumns( final String key, final List<SuperColumn> slice ) {
        final ImmutableList.Builder<T> listBuilder = ImmutableList.builder();
        for ( final SuperColumn superColumn : slice ) {
            final T object = applyColumns( key, superColumn.getColumns() );
            applySuperColumnName( object, superColumn.getName() );
            listBuilder.add( object );
        }
        return listBuilder.build();

    }

    private void applySuperColumnName( final T object, final byte[] value ) {
        final Class<?> returnType = _superColumnPropertyDescriptor.getReadMethod().getReturnType();
        try {
            PropertyUtils.setProperty( object, _superColumnPropertyDescriptor.getName(),
                    _typeConverter.convertByteArrayToValueObject( returnType, value ) );
        } catch ( final IllegalAccessException e ) {
            throw new HelenaRuntimeException( e );
        } catch ( final InvocationTargetException e ) {
            throw new HelenaRuntimeException( e );
        } catch ( final NoSuchMethodException e ) {
            throw new HelenaRuntimeException( e );
        }
    }

    public void delete( final T object ) {
        delete( getKeyFrom( object ) );
    }

    private String getKeyFrom( final T object ) {
        try {
            return _typeConverter.bytesToString( _typeConverter.convertValueObjectToByteArray( PropertyUtils.getProperty( object, _keyPropertyDescriptor.getName() ) ) );
        } catch ( final IllegalAccessException e ) {
            throw new HelenaRuntimeException( e );
        } catch ( final InvocationTargetException e ) {
            throw new HelenaRuntimeException( e );
        } catch ( final NoSuchMethodException e ) {
            throw new HelenaRuntimeException( e );
        }
    }

    public void delete( final String key ) {
        try {
            execute(new Command<Void>(){
                @Override
                public Void execute(final Keyspace ks) throws Exception {
                    ks.remove( key, new ColumnPath( _columnFamily ) );
                    return null;
                }
            });
        } catch ( final Exception e ) {
            throw new HelenaRuntimeException( e );
        }
    }

    public List<T> get( final Iterable<String> keys ) {
        final ColumnParent parent = makeColumnParent();
        final SlicePredicate predicate = makeSlicePredicateWithAllPropertyColumns();
        try {
            return execute(new Command<List<T>>(){
                @Override
                public List<T> execute(final Keyspace ks) throws Exception {

                    final Map<String,List<Column>> slice = ks.multigetSlice( ImmutableList.copyOf( keys ), parent , predicate );

                    return convertToList( slice );

                }
            }); 
        } catch ( final Exception e ) {
            throw new HelenaRuntimeException( e );
        }
    }

    public List<T> getRange( final String keyStart, final String keyEnd, final int amount ) {
        final ColumnParent parent = makeColumnParent();
        final SlicePredicate predicate = makeSlicePredicateWithAllPropertyColumns();
        try {
            return execute(new Command<List<T>>(){
                @Override
                public List<T> execute(final Keyspace ks) throws Exception {

                    final Map<String,List<Column>> slice = ks.getRangeSlice( parent, predicate, keyStart, keyEnd , amount );

                    return convertToList( slice );

                }
            }); 
        } catch ( final Exception e ) {
            throw new HelenaRuntimeException( e );
        }
    }

    public List<T> getSuperRange(final String key, String after, final int limit) {
        final ColumnParent parent = makeColumnParent();
        final SlicePredicate predicate = new SlicePredicate();
        SliceRange sliceRange = new SliceRange();
        sliceRange.setStart(_typeConverter.stringToBytes(after));
        sliceRange.setFinish(new byte[0]);
        sliceRange.setCount(limit);
        predicate.setSlice_range(sliceRange);

        try {
            return execute(new Command<List<T>>(){
                @Override
                public List<T> execute(final Keyspace ks) throws Exception {
                    //try {
                        final Map<String, List<SuperColumn>> slice = ks.getSuperRangeSlice(parent, predicate, key, key, limit);
                        return convertToListSuper(slice);
                    //} //catch (final NotFoundException e) {
                      //  return null;
                    //}
                }

            });
        } catch ( final Exception e ) {
            throw new HelenaRuntimeException( e );
        }
    }

    public List<T> getBySuperColumnProperty(final Iterable<String> values) {
        
        final ColumnParent parent = makeColumnParent();
        final SlicePredicate predicate = makeSlicePredicateWithAllPropertyColumns();

        try {
            return execute(new Command<List<T>>(){
                @Override
                public List<T> execute(final Keyspace ks) throws Exception {

                    final Map<String,List<Column>> slice = ks.multigetSlice( ImmutableList.copyOf( values ), parent , predicate );

                    return convertToList( slice );

                }
            });
        } catch ( final Exception e ) {
            throw new HelenaRuntimeException( e );
        }


    }

    private SlicePredicate makeSlicePredicateWithAllPropertyColumns() {
        final SlicePredicate predicate = new SlicePredicate();
        predicate.setColumn_names( _columnNames );
        return predicate;
    }

    private ColumnParent makeColumnParent() {
        final ColumnParent parent = new ColumnParent();
        parent.setColumn_family( _columnFamily );
        return parent;
    }

    private ColumnPath makeColumnPath(String superColumn) {
        final ColumnPath path = new ColumnPath(_columnFamily);
        path.setSuper_column(_typeConverter.stringToBytes(superColumn));
        return path;
    }



    private List<T> convertToList( final Map<String, List<Column>> slice ) {
        final ImmutableList.Builder<T> listBuilder = ImmutableList.<T>builder();
        for ( final Map.Entry<String, List<Column>> entry : slice.entrySet() ) {
            listBuilder.add( applyColumns( entry.getKey(), entry.getValue() ) ); 
        }
        return listBuilder.build();
    }
    
    private List<T> convertToListSuper( final Map<String, List<SuperColumn>> slice ) {
        final ImmutableList.Builder<T> listBuilder = ImmutableList.<T>builder();
        for ( final Map.Entry<String, List<SuperColumn>> entry : slice.entrySet() ) {
            listBuilder.addAll(applyColumns( entry.getKey(), entry.getValue() ));
        }
        return listBuilder.build();
    }


    public List<T> get( final String key, final Iterable<String> columns ) {
        final ColumnParent parent = makeColumnParent();
        final SlicePredicate predicate = makeSlicePredicateWithColumns( columns );

        try {
            return execute(new Command<List<T>>(){
                @Override
                public List<T> execute(final Keyspace ks) throws Exception {
                    try {
                        final List<SuperColumn> slice = ks.getSuperSlice( key, parent, predicate );

                        return applyColumns( key, slice );
                    } catch (final NotFoundException e) {
                        return null;
                    }
                }

            }); 
        } catch ( final Exception e ) {
            throw new HelenaRuntimeException( e );
        }


    }

    private SlicePredicate makeSlicePredicateWithColumns( final Iterable<String> columns ) {
        final SlicePredicate predicate = new SlicePredicate();
        predicate.setColumn_names( ImmutableList.copyOf( Iterables.transform( columns, _typeConverter.toByteArrayFunction() ) ) );
        return predicate;
    }



}
