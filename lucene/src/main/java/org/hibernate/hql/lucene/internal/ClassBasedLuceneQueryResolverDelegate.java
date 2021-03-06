/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * JBoss, Home of Professional Open Source
 * Copyright 2011 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @authors tag. All rights reserved.
 * See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This copyrighted material is made available to anyone wishing to use,
 * modify, copy, or redistribute it subject to the terms and conditions
 * of the GNU Lesser General Public License, v. 2.1.
 * This program is distributed in the hope that it will be useful, but WITHOUT A
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE.  See the GNU Lesser General Public License for more details.
 * You should have received a copy of the GNU Lesser General Public License,
 * v.2.1 along with this distribution; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA  02110-1301, USA.
 */
package org.hibernate.hql.lucene.internal;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.antlr.runtime.tree.Tree;
import org.hibernate.hql.ast.common.JoinType;
import org.hibernate.hql.ast.origin.hql.resolve.path.PathedPropertyReference;
import org.hibernate.hql.ast.origin.hql.resolve.path.PathedPropertyReferenceSource;
import org.hibernate.hql.ast.origin.hql.resolve.path.PropertyPath;
import org.hibernate.hql.ast.spi.EntityNamesResolver;
import org.hibernate.hql.ast.spi.QueryResolverDelegate;
import org.hibernate.hql.lucene.internal.ast.HSearchEmbeddedEntityTypeDescriptor;
import org.hibernate.hql.lucene.internal.ast.HSearchIndexedEntityTypeDescriptor;
import org.hibernate.hql.lucene.internal.ast.HSearchPropertyTypeDescriptor;
import org.hibernate.hql.lucene.internal.ast.HSearchTypeDescriptor;
import org.hibernate.hql.lucene.internal.builder.ClassBasedLucenePropertyHelper;
import org.hibernate.hql.lucene.internal.logging.Log;
import org.hibernate.hql.lucene.internal.logging.LoggerFactory;

/**
 * This extends the ANTLR generated AST walker to transform a parsed tree
 * into a Lucene Query and collect the target entity types of the query.
 * <br/>
 * <b>TODO:</b>
 *   <li>It is currently human-written but should evolve into another ANTLR
 * generated tree walker, not extending GeneratedHQLResolver but using its
 * output as a generic normalization AST transformer.</li>
 *   <li>We are assembling the Lucene Query directly, but this doesn't take
 *   into account parameter types which might need some transformation;
 *   the Hibernate Search provided {@code QueryBuilder} could do this.</li>
 *   <li>Implement more predicates</li>
 *   <li>Support multiple types being targeted by the Query</li>
 *   <li>Support positional parameters (currently only consumed named parameters)<li>
 *
 * @author Sanne Grinovero <sanne@hibernate.org> (C) 2011 Red Hat Inc.
 * @author Gunnar Morling
 *
 */
public class ClassBasedLuceneQueryResolverDelegate implements QueryResolverDelegate {

	private enum Status {
		DEFINING_SELECT, DEFINING_FROM;
	}

	private static final Log log = LoggerFactory.make();
	/**
	 * Persister space: keep track of aliases and entity names.
	 */
	private final Map<String, String> aliasToEntityType = new HashMap<String, String>();
	private final Map<String, PropertyPath> aliasToPropertyPath = new HashMap<String, PropertyPath>();

	private Status status;

	/**
	 * How to resolve entity names to class instances
	 */
	private final EntityNamesResolver entityNames;

	private final ClassBasedLucenePropertyHelper propertyHelper;

	private Class<?> targetType = null;
	private String alias;

	public ClassBasedLuceneQueryResolverDelegate(ClassBasedLucenePropertyHelper propertyHelper, EntityNamesResolver entityNames) {
		this.entityNames = entityNames;
		this.propertyHelper = propertyHelper;
	}

	/**
	 * See rule entityName
	 */
	@Override
	public void registerPersisterSpace(Tree entityName, Tree alias) {
		String put = aliasToEntityType.put( alias.getText(), entityName.getText() );
		if ( put != null && !put.equalsIgnoreCase( entityName.getText() ) ) {
			throw new UnsupportedOperationException(
					"Alias reuse currently not supported: alias " + alias.getText()
					+ " already assigned to type " + put );
		}
		Class<?> targetedType = entityNames.getClassFromName( entityName.getText() );
		if ( targetedType == null ) {
			throw new IllegalStateException( "Unknown entity name " + entityName.getText() );
		}
		if ( targetType != null ) {
			throw new IllegalStateException( "Can't target multiple types: " + targetType + " already selected before " + targetedType );
		}
		targetType = targetedType;
	}

	@Override
	public boolean isUnqualifiedPropertyReference() {
		return true; // TODO - very likely always true for our supported use cases
	}

	@Override
	public PathedPropertyReferenceSource normalizeUnqualifiedPropertyReference(Tree property) {
		if ( aliasToEntityType.containsKey( property.getText() ) ) {
			return normalizeQualifiedRoot( property );
		}

		return normalizeProperty(
				new HSearchIndexedEntityTypeDescriptor( targetType, propertyHelper ),
				Collections.<String>emptyList(),
				property.getText()
		);
	}

	@Override
	public boolean isPersisterReferenceAlias() {
		return aliasToEntityType.containsKey( alias );
	}

	@Override
	public PathedPropertyReferenceSource normalizeUnqualifiedRoot(Tree identifier382) {
		if ( aliasToEntityType.containsKey( identifier382.getText() ) ) {
			return normalizeQualifiedRoot( identifier382 );
		}
		if ( aliasToPropertyPath.containsKey( identifier382.getText() ) ) {
			PropertyPath propertyPath = aliasToPropertyPath.get( identifier382.getText() );
			if (propertyPath == null ) {
				throw log.getUnknownAliasException( identifier382.getText() );
			}
			HSearchTypeDescriptor sourceType = (HSearchTypeDescriptor) propertyPath.getNodes().get( 0 ).getType();
			List<String> resolveAlias = resolveAlias( propertyPath );

			PathedPropertyReference property = new PathedPropertyReference(
					identifier382.getText(),
					new HSearchEmbeddedEntityTypeDescriptor(
							sourceType.getIndexedEntityType(),
							resolveAlias,
							propertyHelper
					),
					true
			);
			return property;
		}
		throw log.getUnknownAliasException( identifier382.getText() );
	}

	@Override
	public PathedPropertyReferenceSource normalizeQualifiedRoot(Tree root) {
		String entityNameForAlias = aliasToEntityType.get( root.getText() );

		if ( entityNameForAlias == null ) {
			throw log.getUnknownAliasException( root.getText() );
		}

		Class<?> entityType = entityNames.getClassFromName( entityNameForAlias );

		return new PathedPropertyReference(
				root.getText(),
				new HSearchIndexedEntityTypeDescriptor( entityType, propertyHelper ),
				true
		);
	}

	@Override
	public PathedPropertyReferenceSource normalizePropertyPathIntermediary(
			PropertyPath path, Tree propertyName) {

		HSearchTypeDescriptor sourceType = (HSearchTypeDescriptor) path.getLastNode().getType();

		if ( !sourceType.hasProperty( propertyName.getText() ) ) {
			throw log.getNoSuchPropertyException( sourceType.toString(), propertyName.getText() );
		}

		List<String> newPath = resolveAlias( path );
		newPath.add( propertyName.getText() );

		PathedPropertyReference property = new PathedPropertyReference(
				propertyName.getText(),
				new HSearchEmbeddedEntityTypeDescriptor(
						sourceType.getIndexedEntityType(),
						newPath,
						propertyHelper
				),
				false
		);

		return property;
	}

	@Override
	public PathedPropertyReferenceSource normalizeIntermediateIndexOperation(
			PathedPropertyReferenceSource propertyReferenceSource, Tree collectionProperty, Tree selector) {
		throw new UnsupportedOperationException( "Not yet implemented" );
	}

	@Override
	public void normalizeTerminalIndexOperation(
			PathedPropertyReferenceSource propertyReferenceSource, Tree collectionProperty, Tree selector) {
		throw new UnsupportedOperationException( "Not yet implemented" );
	}

	@Override
	public PathedPropertyReferenceSource normalizeUnqualifiedPropertyReferenceSource(Tree identifier394) {
		throw new UnsupportedOperationException( "Not yet implemented" );
	}

	@Override
	public PathedPropertyReferenceSource normalizePropertyPathTerminus(PropertyPath path, Tree propertyNameNode) {
		// receives the property name on a specific entity reference _source_
		HSearchTypeDescriptor type = (HSearchTypeDescriptor) path.getLastNode().getType();
		return normalizeProperty( type, path.getNodeNamesWithoutAlias(), propertyNameNode.getText() );
	}

	private List<String> resolveAlias(PropertyPath path) {
		if ( path.getFirstNode().isAlias() ) {
			String alias = path.getFirstNode().getName();
			if ( aliasToEntityType.containsKey( alias ) ) {
				// Alias for entity
				return path.getNodeNamesWithoutAlias();
			}
			else if ( aliasToPropertyPath.containsKey( alias ) ) {
				// Alias for embedded
				PropertyPath propertyPath = aliasToPropertyPath.get( alias );
				List<String> resolvedAlias = resolveAlias( propertyPath );
				resolvedAlias.addAll( path.getNodeNamesWithoutAlias() );
				return resolvedAlias;
			}
			else {
				// Alias not found
				throw log.getUnknownAliasException( alias );
			}
		}
		// It does not start with an alias
		return path.getNodeNamesWithoutAlias();
	}

	private PathedPropertyReferenceSource normalizeProperty(HSearchTypeDescriptor type, List<String> path, String propertyName) {
		if ( !type.hasProperty( propertyName ) ) {
			throw log.getNoSuchPropertyException( type.toString(), propertyName );
		}

		if ( status != Status.DEFINING_SELECT && !type.isEmbedded( propertyName ) && type.isAnalyzed( propertyName ) ) {
			throw log.getQueryOnAnalyzedPropertyNotSupportedException( type.getIndexedEntityType().getCanonicalName(), propertyName );
		}

		if ( type.isEmbedded( propertyName ) ) {
			List<String> newPath = new LinkedList<String>( path );
			newPath.add( propertyName );
			return new PathedPropertyReference(
					propertyName,
					new HSearchEmbeddedEntityTypeDescriptor( type.getIndexedEntityType(), newPath, propertyHelper ),
					false)
			;
		}
		else {
			return new PathedPropertyReference(
					propertyName,
					new HSearchPropertyTypeDescriptor(),
					false
			);
		}
	}

	@Override
	public void pushFromStrategy(
			JoinType joinType,
			Tree assosiationFetchTree,
			Tree propertyFetchTree,
			Tree alias) {
		status = Status.DEFINING_FROM;
		this.alias = alias.getText();
	}

	@Override
	public void pushSelectStrategy() {
		status = Status.DEFINING_SELECT;
	}

	@Override
	public void popStrategy() {
		status = null;
		this.alias = null;
	}

	@Override
	public void propertyPathCompleted(PropertyPath path) {
		if ( status == Status.DEFINING_SELECT && path.getLastNode().getType() instanceof HSearchEmbeddedEntityTypeDescriptor ) {
			HSearchEmbeddedEntityTypeDescriptor type = (HSearchEmbeddedEntityTypeDescriptor) path.getLastNode().getType();

			throw log.getProjectionOfCompleteEmbeddedEntitiesNotSupportedException(
					type.getIndexedEntityType().getCanonicalName(),
					path.asStringPathWithoutAlias()
			);
		}
	}

	@Override
	public void registerJoinAlias(Tree alias, PropertyPath path) {
		if ( !path.getNodes().isEmpty() && !aliasToPropertyPath.containsKey( alias.getText() ) ) {
			aliasToPropertyPath.put( alias.getText(), path );
		}
	}
}
