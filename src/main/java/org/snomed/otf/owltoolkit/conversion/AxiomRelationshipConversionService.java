package org.snomed.otf.owltoolkit.conversion;

import static org.snomed.otf.owltoolkit.ontology.OntologyService.CORE_COMPONENT_NAMESPACE_PATTERN;
import static org.snomed.otf.owltoolkit.ontology.OntologyService.SNOMED_ROLE_GROUP_FULL_URI;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.semanticweb.owlapi.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snomed.otf.owltoolkit.constants.Concepts;
import org.snomed.otf.owltoolkit.domain.AxiomRepresentation;
import org.snomed.otf.owltoolkit.domain.ObjectPropertyAxiomRepresentation;
import org.snomed.otf.owltoolkit.domain.Relationship;
import org.snomed.otf.owltoolkit.ontology.OntologyHelper;
import org.snomed.otf.owltoolkit.ontology.OntologyService;
import org.snomed.otf.owltoolkit.taxonomy.AxiomDeserialiser;

public class AxiomRelationshipConversionService {

	private static final Logger LOGGER = LoggerFactory.getLogger(AxiomRelationshipConversionService.class);

	private final AxiomDeserialiser axiomDeserialiser;
	private final OntologyService ontologyService;

	private Collection<Long> objectAttributes;
	private Collection<Long> dataAttributes;
	private Collection<Long> annotationAttributes;

	public AxiomRelationshipConversionService(Set<Long> ungroupedAttributes) {
		this.axiomDeserialiser = new AxiomDeserialiser();
		this.ontologyService = new OntologyService(ungroupedAttributes);
	}

	/**
	 * Use this constructor to enable generating SubObjectPropertyOf and SubDataPropertyOf axioms from relationships.
	 * @param ungroupedAttributes A set of concept identifiers from the referencedComponentIds of rows in the MRCM Attribute Domain reference set which have group=0.
	 * @param objectAttributes A set of concept identifiers from the descendants of 762705008 |Concept model object attribute (attribute)|.
	 * @param dataAttributes A set of concept identifiers from the descendants of 762706009 |Concept model data attribute (attribute)|.
	 */
	public AxiomRelationshipConversionService(Set<Long> ungroupedAttributes, Collection<Long> objectAttributes, Collection<Long> dataAttributes) {
		this(ungroupedAttributes);
		this.objectAttributes = objectAttributes;
		this.dataAttributes = dataAttributes;
	}

	/**
	 * Use this constructor to enable generating SubObjectPropertyOf, SubDataPropertyOf and SubAnnotationPropertyOf axioms from relationships.
	 * @param ungroupedAttributes A set of concept identifiers from the referencedComponentIds of rows in the MRCM Attribute Domain reference set which have group=0.
	 * @param objectAttributes A set of concept identifiers from the descendants of 762705008 |Concept model object attribute (attribute)|.
	 * @param dataAttributes A set of concept identifiers from the descendants of 762706009 |Concept model data attribute (attribute)|.
	 * @param annotationAttributes A set of concept identifiers from the descendants of 1295447006 |Annotation attribute (attribute)|.
	 */
	public AxiomRelationshipConversionService(Set<Long> ungroupedAttributes, Collection<Long> objectAttributes, Collection<Long> dataAttributes, Collection<Long> annotationAttributes) {
		this(ungroupedAttributes, objectAttributes, dataAttributes);
		this.annotationAttributes = annotationAttributes;
	}

	/**
	 * Converts an OWL Axiom expression String to an AxiomRepresentation containing a concept id or set of relationships for each side of the expression.
	 * Currently supported axiom types are SubClassOf, EquivalentClasses and SubObjectPropertyOf.
	 *
	 * @param axiomExpression The Axiom expression to convert.
	 * @return AxiomRepresentation with the details of the expression or null if the axiom type is not supported.
	 * @throws ConversionException if the Axiom expression is malformed or of an unexpected structure.
	 */
	public AxiomRepresentation convertAxiomToRelationships(String axiomExpression) throws ConversionException {
		OWLAxiom owlAxiom = convertOwlExpressionToOWLAxiom(axiomExpression);
		return convertAxiomToRelationships(owlAxiom);
	}
	
	/**
	 * Converts an OWL Axiom expression String to an ObjectPropertyAxiomRepresentation 
	 * indicating if transient, reflexive or the head of a role chain.
	 * @param axiomExpression The Axiom expression to convert.
	 * @return ObjectPropertyAxiomRepresentation with the details of the expression or null if the axiom type is not supported.
	 * @throws ConversionException if the Axiom expression is malformed or of an unexpected structure.
	 */
	public ObjectPropertyAxiomRepresentation asObjectPropertyAxiom(String axiomExpression) throws ConversionException {
		OWLAxiom owlAxiom = convertOwlExpressionToOWLAxiom(axiomExpression);
		ObjectPropertyAxiomRepresentation axiom = new ObjectPropertyAxiomRepresentation(axiomExpression);
		if (owlAxiom.getAxiomType() == AxiomType.TRANSITIVE_OBJECT_PROPERTY) {
			axiom.setTransitive(true);
		} else if (owlAxiom.getAxiomType() == AxiomType.REFLEXIVE_OBJECT_PROPERTY) {
			axiom.setReflexive(true);
		} else if (owlAxiom.getAxiomType() == AxiomType.SUB_PROPERTY_CHAIN_OF) {
			axiom.setPropertyChain(true);
		}
		return axiom;
	}

	/**
	 * Converts an OWL Axiom expression String to an AxiomRepresentation containing a concept id or set of relationships for each side of the expression.
	 * Currently supported axiom types are SubClassOf, EquivalentClasses, SubObjectPropertyOf and SubDataPropertyOf.
	 *
	 * @param owlAxiom The Axiom expression to convert.
	 * @return AxiomRepresentation with the details of the expression or null if the axiom type is not supported.
	 * @throws ConversionException if the Axiom expression is malformed or of an unexpected structure.
	 */
	public AxiomRepresentation convertAxiomToRelationships(OWLAxiom owlAxiom) throws ConversionException {
		return convertAxiomToRelationships(owlAxiom, new AtomicInteger(1));
	}

	/**
	 * Converts an OWL Axiom expression String to an AxiomRepresentation containing a concept id or set of relationships for each side of the expression.
	 * Currently supported axiom types are SubClassOf, EquivalentClasses, SubObjectPropertyOf and SubDataPropertyOf.
	 *
	 * @param owlAxiom    The Axiom expression to convert.
	 * @param groupOffset The starting number for inferred role groups. This can be used to ensure separation of groups for Concepts with multiple Axioms.
	 * @return AxiomRepresentation with the details of the expression or null if the axiom type is not supported.
	 * @throws ConversionException if the Axiom expression is malformed or of an unexpected structure.
	 */
	public AxiomRepresentation convertAxiomToRelationships(OWLAxiom owlAxiom, AtomicInteger groupOffset) throws ConversionException {
		AxiomType<?> axiomType = owlAxiom.getAxiomType();

		if (axiomType != AxiomType.SUBCLASS_OF && axiomType != AxiomType.EQUIVALENT_CLASSES &&
				axiomType != AxiomType.SUB_OBJECT_PROPERTY && axiomType != AxiomType.SUB_DATA_PROPERTY && axiomType != AxiomType.SUB_ANNOTATION_PROPERTY_OF) {
			LOGGER.debug("Only SubClassOf, EquivalentClasses, SubObjectPropertyOf and SubDataPropertyOf can be converted to relationships. " +
					"Axiom given is of type  \"{}\". Returning null.",  axiomType.getName());
			return null;
		}

		AxiomRepresentation representation = new AxiomRepresentation();
		OWLClassExpression leftHandExpression;
		OWLClassExpression rightHandExpression;

		if (axiomType == AxiomType.SUB_OBJECT_PROPERTY) {
			OWLSubObjectPropertyOfAxiom subObjectPropertyOfAxiom = (OWLSubObjectPropertyOfAxiom) owlAxiom;

			OWLObjectPropertyExpression subProperty = subObjectPropertyOfAxiom.getSubProperty();
			OWLObjectProperty namedProperty = subProperty.getNamedProperty();
			long subAttributeConceptId = OntologyHelper.getConceptId(namedProperty);

			OWLObjectPropertyExpression superProperty = subObjectPropertyOfAxiom.getSuperProperty();
			OWLObjectProperty superPropertyNamedProperty = superProperty.getNamedProperty();
			long superAttributeConceptId = OntologyHelper.getConceptId(superPropertyNamedProperty);

			representation.setLeftHandSideNamedConcept(subAttributeConceptId);
			representation.setRightHandSideRelationships(newSingleIsARelationship(superAttributeConceptId));
			representation.setPrimitive(true);

			return representation;

		} else if (axiomType == AxiomType.SUB_DATA_PROPERTY) {
			OWLSubDataPropertyOfAxiom subDataPropertyOfAxiom = (OWLSubDataPropertyOfAxiom) owlAxiom;

			OWLDataPropertyExpression subProperty = subDataPropertyOfAxiom.getSubProperty();
			OWLDataProperty namedProperty = subProperty.getDataPropertiesInSignature().iterator().next();
			long subAttributeConceptId = OntologyHelper.getConceptId(namedProperty);

			OWLDataPropertyExpression superProperty = subDataPropertyOfAxiom.getSuperProperty();
			OWLDataProperty superPropertyNamedProperty = superProperty.getDataPropertiesInSignature().iterator().next();
			long superAttributeConceptId = OntologyHelper.getConceptId(superPropertyNamedProperty);

			representation.setLeftHandSideNamedConcept(subAttributeConceptId);
			representation.setRightHandSideRelationships(newSingleIsARelationship(superAttributeConceptId));
			representation.setPrimitive(true);

			return representation;

		} else if (axiomType == AxiomType.SUB_ANNOTATION_PROPERTY_OF) {
			OWLSubAnnotationPropertyOfAxiom subAnnotationPropertyOfAxiom = (OWLSubAnnotationPropertyOfAxiom) owlAxiom;

			OWLAnnotationProperty subProperty = subAnnotationPropertyOfAxiom.getSubProperty();
			OWLAnnotationProperty namedProperty = subProperty.getAnnotationPropertiesInSignature().iterator().next();
			long subAttributeConceptId = OntologyHelper.getConceptId(namedProperty);

			OWLAnnotationProperty superProperty = subAnnotationPropertyOfAxiom.getSuperProperty();
			OWLAnnotationProperty superPropertyNamedProperty = superProperty.getAnnotationPropertiesInSignature().iterator().next();
			long superAttributeConceptId = OntologyHelper.getConceptId(superPropertyNamedProperty);

			representation.setLeftHandSideNamedConcept(subAttributeConceptId);
			representation.setRightHandSideRelationships(newSingleIsARelationship(superAttributeConceptId));
			representation.setPrimitive(true);

			return representation;

		} else if (axiomType == AxiomType.EQUIVALENT_CLASSES) {
			OWLEquivalentClassesAxiom equivalentClassesAxiom = (OWLEquivalentClassesAxiom) owlAxiom;
			Set<OWLClassExpression> classExpressions = equivalentClassesAxiom.getClassExpressions();
			if (classExpressions.size() != 2) {
				throw new ConversionException("Expecting EquivalentClasses expression to contain 2 expressions, got " + classExpressions.size() + " - axiom '" + owlAxiom.toString() + "'.");
			}
			Iterator<OWLClassExpression> iterator = classExpressions.iterator();
			leftHandExpression = iterator.next();
			rightHandExpression = iterator.next();
		} else {
			representation.setPrimitive(true);
			OWLSubClassOfAxiom subClassOfAxiom = (OWLSubClassOfAxiom) owlAxiom;
			leftHandExpression = subClassOfAxiom.getSubClass();
			rightHandExpression = subClassOfAxiom.getSuperClass();
		}

		Long leftNamedClass = getNamedClass(owlAxiom, leftHandExpression, "left");
		Long rightNamedClass = getNamedClass(owlAxiom, rightHandExpression, "right");
		if (leftNamedClass != null) {
			// Normal axiom
			representation.setLeftHandSideNamedConcept(leftNamedClass);
			representation.setRightHandSideRelationships(rightNamedClass != null ? newSingleIsARelationship(rightNamedClass) : getRelationships(rightHandExpression, groupOffset));
		} else {
			// GCI
			representation.setLeftHandSideRelationships(getRelationships(leftHandExpression, new AtomicInteger(1))); // Default offset as GCI Axioms do not contribute to necessary normal form
			if (rightNamedClass == null) {
				throw new ConversionException("Axioms with expressions on both sides are not supported.");
			}
			representation.setRightHandSideNamedConcept(rightNamedClass);
		}

		return representation;
	}

	private Map<Integer, List<Relationship>> newSingleIsARelationship(Long leftNamedClass) {
		Map<Integer, List<Relationship>> relationships = new HashMap<>();
		relationships.put(0, Collections.singletonList(new Relationship(0, Concepts.IS_A_LONG, leftNamedClass)));
		return relationships;
	}

	/**
	 * 	Currently supported axiom types are SubClassOf and EquivalentClasses - axioms of other types will be ignored.
	 */
	public Map<Long, Set<AxiomRepresentation>> convertAxiomsToRelationships(Map<Long, List<OWLAxiom>> conceptAxiomMap, boolean ignoreGCIAxioms) throws ConversionException {
		Map<Long, Set<AxiomRepresentation>> conceptAxiomStatements = new HashMap<>();
		OWLAxiom currentAxiom = null;
		try {
			for (Long conceptId : conceptAxiomMap.keySet()) {
				Collection<OWLAxiom> axioms = conceptAxiomMap.get(conceptId);
				AtomicInteger groupOffset = new AtomicInteger(1); // Skipping to 1 as 0 reserved for non-grouped
				for (OWLAxiom axiom : axioms) {
					currentAxiom = axiom;
					boolean ignore = false;
					if (ignoreGCIAxioms && axiom instanceof OWLSubClassOfAxiom) {
						OWLSubClassOfAxiom classOfAxiom = (OWLSubClassOfAxiom) axiom;
						ignore = classOfAxiom.isGCI();
					}
					if (!ignore) {
						AxiomRepresentation axiomRepresentation = convertAxiomToRelationships(axiom, groupOffset);
						if (axiomRepresentation != null) {
							conceptAxiomStatements.computeIfAbsent(conceptId, id -> new HashSet<>()).add(axiomRepresentation);
						}
					}
				}
			}
		} catch (ConversionException e) {
			LOGGER.error("Failed to convert axiom \"{}\".", currentAxiom.toString(), e);
			throw e;
		}
		return conceptAxiomStatements;
	}

	public String convertRelationshipsToAxiom(AxiomRepresentation representation) throws ConversionException {

		// Identify and convert object and data property axioms
		if (representation.getLeftHandSideNamedConcept() != null && representation.getRightHandSideRelationships() != null) {
			final Map<Integer, List<Relationship>> rightHandSideRelationships = representation.getRightHandSideRelationships();
			if (!rightHandSideRelationships.containsKey(0)) {
				throw new ConversionException("At least one relationship is required in group 0.");
			}
			if (rightHandSideRelationships.get(0).stream().noneMatch(relationship -> Concepts.IS_A_LONG == relationship.getTypeId())) {
				throw new ConversionException("At least one relationship with type '116680003 | Is a (attribute) |' is required in group 0.");
			}
			List<Relationship> relationships = rightHandSideRelationships.get(0);
			for (Relationship relationship : relationships) {
				if (relationship.getTypeId() == Concepts.IS_A_LONG) {
					if (annotationAttributes != null && annotationAttributes.contains(relationship.getDestinationId())) {
						return axiomToString(ontologyService.createOwlSubAnnotationPropertyOfAxiom(representation.getLeftHandSideNamedConcept(), relationship.getDestinationId()));
					} else if (objectAttributes != null && objectAttributes.contains(relationship.getDestinationId())) {
						// Attribute concepts will only have one parent
						return axiomToString(ontologyService.createOwlSubObjectPropertyOfAxiom(representation.getLeftHandSideNamedConcept(), relationship.getDestinationId()));
					} else if (dataAttributes != null && dataAttributes.contains(relationship.getDestinationId())) {
						return axiomToString(ontologyService.createOwlSubDataPropertyOfAxiom(representation.getLeftHandSideNamedConcept(), relationship.getDestinationId()));
					} else {
						// If the first parent is not an attribute then the concept is not an attribute.
						break;
					}
				}
			}
		}

		// Normal axioms and GCI axioms go through here
		return axiomToString(ontologyService.createOwlClassAxiom(representation));
	}

	public String axiomToString(OWLAxiom owlAxiom) {
		return owlAxiom.toString().replaceAll(CORE_COMPONENT_NAMESPACE_PATTERN, ":$1").replace(") )", "))");
	}

	/**
	 * Extracts all concept ids from any axiom type.
	 * This is intended for validation purposes.
	 *
	 * @param axiomExpression The Axiom expression to extract from.
	 * @return AxiomRepresentation with the details of the expression or null if the axiom type is not supported.
	 * @throws ConversionException if the Axiom expression is malformed or of an unexpected structure.
	 */
	public Set<Long> getIdsOfConceptsNamedInAxiom(String axiomExpression) throws ConversionException {
		OWLAxiom owlAxiom = convertOwlExpressionToOWLAxiom(axiomExpression);
		return owlAxiom.getSignature().stream().filter(OntologyHelper::isNamedConcept).map(OntologyHelper::getConceptId).collect(Collectors.toSet());
	}

	private OWLAxiom convertOwlExpressionToOWLAxiom(String axiomExpression) throws ConversionException {
		OWLAxiom owlAxiom;
		try {
			owlAxiom = axiomDeserialiser.deserialiseAxiom(axiomExpression, null);
		} catch (OWLOntologyCreationException e) {
			throw new ConversionException("Failed to deserialise axiom expression '" + axiomExpression + "'.");
		}
		return owlAxiom;
	}

	private Long getNamedClass(OWLAxiom owlAxiom, OWLClassExpression owlClassExpression, String side) throws ConversionException {
		if (owlClassExpression.getClassExpressionType() != ClassExpressionType.OWL_CLASS) {
			return null;
		}
		Set<OWLClass> classesInSignature = owlClassExpression.getClassesInSignature();
		if (classesInSignature.size() > 1) {
			throw new ConversionException("Expecting a maximum of 1 class in " + side + " hand side of axiom, got " + classesInSignature.size() + " - axiom '" + owlAxiom.toString() + "'.");
		}

		if (classesInSignature.size() == 1) {
			OWLClass namedClass = classesInSignature.iterator().next();
			return OntologyHelper.getConceptId(namedClass);
		}
		return null;
	}

	private Map<Integer, List<Relationship>> getRelationships(OWLClassExpression owlClassExpression, AtomicInteger groupOffset) throws ConversionException {
		if (owlClassExpression.getClassExpressionType() != ClassExpressionType.OBJECT_INTERSECTION_OF) {
			throw new ConversionException("Expecting ObjectIntersectionOf at first level of expression, got " + owlClassExpression.getClassExpressionType() + " in expression " + owlClassExpression.toString() + ".");
		}

		OWLObjectIntersectionOf intersectionOf = (OWLObjectIntersectionOf) owlClassExpression;
		List<OWLClassExpression> expressions = intersectionOf.getOperandsAsList();

		Map<Integer, List<Relationship>> relationshipGroups = new HashMap<>();
		for (OWLClassExpression operand : expressions) {
			ClassExpressionType operandClassExpressionType = operand.getClassExpressionType();
			if (operandClassExpressionType == ClassExpressionType.OWL_CLASS) {
				// Is-a relationship
				relationshipGroups.computeIfAbsent(0, key -> new ArrayList<>()).add(new Relationship(0, Concepts.IS_A_LONG, OntologyHelper.getConceptId(operand.asOWLClass())));

			} else if (operandClassExpressionType == ClassExpressionType.OBJECT_SOME_VALUES_FROM) {
				// Either start of attribute or role group
				OWLObjectSomeValuesFrom someValuesFrom = (OWLObjectSomeValuesFrom) operand;
				OWLObjectPropertyExpression property = someValuesFrom.getProperty();
				if (isRoleGroup(property)) {
					// Extract Group
					int rollingGroupNumber = groupOffset.get();
					OWLClassExpression filler = someValuesFrom.getFiller();
					if (filler.getClassExpressionType() == ClassExpressionType.OBJECT_SOME_VALUES_FROM) {
						Relationship relationship = extractRelationship((OWLObjectSomeValuesFrom) filler, rollingGroupNumber);
						relationshipGroups.computeIfAbsent(rollingGroupNumber, key -> new ArrayList<>()).add(relationship);
					} else if (filler.getClassExpressionType() == ClassExpressionType.DATA_HAS_VALUE) {
						Relationship relationship = extractRelationshipConcreteValue((OWLDataHasValue) filler, rollingGroupNumber);
						relationshipGroups.computeIfAbsent(rollingGroupNumber, key -> new ArrayList<>()).add(relationship);
					} else if (filler.getClassExpressionType() == ClassExpressionType.OBJECT_INTERSECTION_OF) {
						OWLObjectIntersectionOf listOfAttributes = (OWLObjectIntersectionOf) filler;
						for (OWLClassExpression classExpression : listOfAttributes.getOperandsAsList()) {
							if (classExpression.getClassExpressionType() == ClassExpressionType.OBJECT_SOME_VALUES_FROM) {
								Relationship relationship = extractRelationship((OWLObjectSomeValuesFrom) classExpression, rollingGroupNumber);
								relationshipGroups.computeIfAbsent(rollingGroupNumber, key -> new ArrayList<>()).add(relationship);
							} else if (classExpression.getClassExpressionType() == ClassExpressionType.DATA_HAS_VALUE) {
								Relationship relationship = extractRelationshipConcreteValue((OWLDataHasValue) classExpression, rollingGroupNumber);
								relationshipGroups.computeIfAbsent(rollingGroupNumber, key -> new ArrayList<>()).add(relationship);
							} else {
								throw new ConversionException("Expecting ObjectSomeValuesFrom or DataHasValue within ObjectIntersectionOf as part of role group, " +
										"got " + classExpression.getClassExpressionType() + " in expression " + owlClassExpression.toString() + ".");
							}
						}
					} else {
						throw new ConversionException("Expecting ObjectSomeValuesFrom with role group to have one of ObjectSomeValuesFrom, DataHasValue or ObjectIntersectionOf, " +
								"got " + filler.getClassExpressionType() + " in expression " + owlClassExpression.toString() + ".");
					}
					groupOffset.incrementAndGet();
				} else {
					Relationship relationship = extractRelationship(someValuesFrom, 0);
					relationshipGroups.computeIfAbsent(0, key -> new ArrayList<>()).add(relationship);
				}
			} else if (operandClassExpressionType == ClassExpressionType.DATA_HAS_VALUE) {
				Relationship relationship = extractRelationshipConcreteValue((OWLDataHasValue) operand, 0);
				relationshipGroups.computeIfAbsent(0, key -> new ArrayList<>()).add(relationship);
			} else {
				throw new ConversionException("Expecting Class or ObjectSomeValuesFrom or DataHasValue at second level of expression, got " + operandClassExpressionType + " in expression " + owlClassExpression.toString() + ".");
			}
		}

		return relationshipGroups;
	}

	private Relationship extractRelationshipConcreteValue(OWLDataHasValue dataHasValue, int groupNumber) throws ConversionException {
		OWLDataPropertyExpression property = dataHasValue.getProperty();
		OWLDataProperty dataProperty = property.asOWLDataProperty();
		long typeId = OntologyHelper.getConceptId(dataProperty);

		OWLLiteral filler = dataHasValue.getFiller();
		OWLDatatype datatype = filler.getDatatype();
		String value = filler.getLiteral();
		Relationship.ConcreteValue.Type valueType = null;
		if (!datatype.isBuiltIn()) {
			throw new ConversionException(datatype.toString() + " is not an OWL builtIn data type.");
		}
		if (datatype.isBuiltIn()) {
			switch (datatype.getBuiltInDatatype()) {
				case XSD_DECIMAL :
					valueType = Relationship.ConcreteValue.Type.DECIMAL;
					break;
				case XSD_INTEGER :
					valueType = Relationship.ConcreteValue.Type.INTEGER;
					break;
				case XSD_STRING :
					valueType = Relationship.ConcreteValue.Type.STRING;
					break;
				default :
					throw new ConversionException("Unsupported OWLDataType " + datatype.toString());
			}
		}
		return new Relationship(groupNumber, typeId, new Relationship.ConcreteValue(valueType, value));
	}

	private Relationship extractRelationship(OWLObjectSomeValuesFrom someValuesFrom, int groupNumber) throws ConversionException {
		OWLObjectPropertyExpression property = someValuesFrom.getProperty();
		OWLObjectProperty namedProperty = property.getNamedProperty();
		long type = OntologyHelper.getConceptId(namedProperty);

		OWLClassExpression filler = someValuesFrom.getFiller();
		ClassExpressionType classExpressionType = filler.getClassExpressionType();
		if (classExpressionType != ClassExpressionType.OWL_CLASS) {
			throw new ConversionException("Expecting right hand side of ObjectSomeValuesFrom to be type Class, got " + classExpressionType + ".");
		}
		long value = OntologyHelper.getConceptId(filler.asOWLClass());

		return new Relationship(groupNumber, type, value);
	}

	private boolean isRoleGroup(OWLObjectPropertyExpression expression) {
		OWLObjectProperty namedProperty = expression.getNamedProperty();
		return SNOMED_ROLE_GROUP_FULL_URI.equals(namedProperty.getIRI().toString());
	}
}
