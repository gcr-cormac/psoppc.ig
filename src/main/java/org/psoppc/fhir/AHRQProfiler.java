package org.psoppc.fhir;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;

import org.eclipse.emf.ecore.EAnnotation;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.hl7.fhir.ElementDefinition;
import org.hl7.fhir.ElementDefinitionBinding;
import org.hl7.fhir.ElementDefinitionDiscriminator;
import org.hl7.fhir.ElementDefinitionSlicing;
import org.hl7.fhir.StructureDefinition;
import org.hl7.fhir.StructureDefinitionSnapshot;
import org.hl7.fhir.UnsignedInt;
import org.hl7.fhir.emf.FHIRSerDeser;
import org.hl7.fhir.emf.Finals;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.spi.OptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AHRQProfiler implements Runnable {

	private static final Logger log = LoggerFactory.getLogger(AHRQProfiler.class);

	public static final java.lang.String ECORE_GENMODEL_URL = "http://www.eclipse.org/emf/2002/GenModel";
	public static final java.lang.String HL7_FHIR_URL = "http://hl7.org/fhir";

    private CmdLineParser CLI;

    @Option(name = "-p", aliases = "--profile", required = false, usage = "Path to the profile")
    private String profile;

    @Option(name = "-i", aliases = "--input", required = false, usage = "Path to fhir.ecore")
    private String input;

    @Option(name = "-o", aliases = "--output", required = false, usage = "Path to out.ecore.")
    private String output;

	@Option(name = "-h", aliases = {"--help"}, help = true, usage = "Display help")
	private boolean help;

    public AHRQProfiler(String[] args) throws CmdLineException {
        try {
			CLI = new CmdLineParser(AHRQProfiler.this);
            CLI.parseArgument(args);
        } catch (CmdLineException e) {
            log.error("", e);
        }
    }


	public void run() {
		StructureDefinition profile = loadProfile();
		EPackage spec = loadSpec();
		EPackage out = copySpec(spec);
		clearClassifiers(out);
		StructureDefinitionSnapshot snap = profile.getSnapshot();
		populateEcoreOut(snap, spec, out);
		OutputStream writer = FHIRSerDeser.save(out, Finals.SDS_FORMAT.ECORE);
		try {
			FileWriter fileOut = new FileWriter(new File(output));
			fileOut.write(writer.toString());
			fileOut.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

    public boolean isHelp() {
        return help;
    }

    private void printUsage() {
        System.out.println("Usage:");
        CLI.printUsage(System.out);
    }

    public void populateEcoreOut(StructureDefinitionSnapshot snap, EPackage spec, EPackage out) {
        for (ElementDefinition elem : snap.getElement()) {
            String path = elem.getPath().getValue(); // e.g., "AdverseEvent.actuality"

            String[] parts = path.split("\\.");
            if (parts.length < 2) continue; // Skip root or malformed entries

            String elemClassName = parts[0];
            String elemFeatureName = parts[1];

            // Find the source EClass in the spec package

            EClassifier elemClassifier = spec.getEClassifier(elemClassName);
            if (!(isInSpec(elemClassName, spec))) {
                log.error("⚠️ Could not find EClass " + elemClassName + " in spec.");
                continue;
            }

            // Copy or create the EClass in the output package
            EClass outClassifier = (EClass) out.getEClassifier(elemClassName);
            if (outClassifier == null) {
                outClassifier = EcoreFactory.eINSTANCE.createEClass();
                outClassifier.setName(elemClassName);
                out.getEClassifiers().add(outClassifier);
            }

            // Find the feature in the spec EClass
            EStructuralFeature elemFeature = elemClassifier.eClass().getEStructuralFeature(elemFeatureName);
            if (elemFeature == null) {
                log.error("⚠️ Could not find feature " + elemFeatureName + " in " + elemFeatureName);
                continue;
            }

            // Copy the feature
            EStructuralFeature copiedFeature = copyFeature(elemFeature);
            outClassifier.getEStructuralFeatures().add(copiedFeature);

            // Optional: apply snapshot constraints
            applySnapshotElementToFeature(elem, copiedFeature);
			applySlice(elem, copiedFeature);
        }
    }

	Boolean isInSpec(String elemClassName, EPackage spec) {
		EClassifier elemClassifier = spec.getEClassifier(elemClassName);
		if (elemClassifier == null) {
			return false;
		 } else {
			return (elemClassifier instanceof EClass);
		 }
	}

	public void applyBounds(ElementDefinition snapshotElem, EStructuralFeature outFeature) {
		applyLowerBounds(snapshotElem, outFeature);
		applyUpperBounds(snapshotElem, outFeature);
	}

	public void applyLowerBounds(ElementDefinition snapshotElem, EStructuralFeature outFeature) {
		UnsignedInt uint = snapshotElem.getMin();
		BigInteger bigInt = uint.getValue();
		Integer min = bigInt.intValue();

		if (min != null) {
			outFeature.setLowerBound(min);
		}
	}

	public void applyUpperBounds(ElementDefinition snapshotElem, EStructuralFeature outFeature) {
		String maxStr = snapshotElem.getMax().getValue();
		if (maxStr != null) {
			if ("*".equals(maxStr)) {
				outFeature.setUpperBound(EStructuralFeature.UNBOUNDED_MULTIPLICITY);
			} else {
				try {
					outFeature.setUpperBound(Integer.parseInt(maxStr));
				} catch (NumberFormatException e) {
					System.err.println("⚠️ Invalid max cardinality: " + maxStr);
				}
			}
		}
	}

	public void applySlice(ElementDefinition snapshotElem, EStructuralFeature outFeature) {

		ElementDefinitionSlicing slicing = snapshotElem.getSlicing();

		if (slicing == null) {
			return;
		}

		// Create an annotation for the slicing metadata
		EAnnotation slicingAnnotation = EcoreFactory.eINSTANCE.createEAnnotation();
		slicingAnnotation.setSource("http://hl7.org/fhir/slicing");

		// Add discriminator(s)
		int index = 0;
		for (ElementDefinitionDiscriminator discriminator : slicing.getDiscriminator()) {
			String type = discriminator.getType().getValue().getLiteral();
			String path = discriminator.getPath().getValue();
			slicingAnnotation.getDetails().put("discriminator:" + index, type + ":" + path);
			index++;
		}

		// Add other slicing attributes
		if (slicing.getRules() != null) {
			slicingAnnotation.getDetails().put("rules", slicing.getRules().getValue().getLiteral());
		}
		if (slicing.getOrdered() != null) {
			slicingAnnotation.getDetails().put("ordered", slicing.getOrdered().toString());
		}
		if (slicing.getDescription() != null && !slicing.getDescription().getValue().isEmpty()) {
			slicingAnnotation.getDetails().put("description", slicing.getDescription().getValue());
		}

		// Attach the annotation to the EStructuralFeature
		outFeature.getEAnnotations().add(slicingAnnotation);
	}


	StructureDefinition loadProfile() {
		InputStream reader = AHRQProfiler.class.getClassLoader()
			.getResourceAsStream(profile);
		return (StructureDefinition) FHIRSerDeser.load(reader, Finals.SDS_FORMAT.XML);
	}

	EPackage loadSpec() {
		InputStream reader = AHRQProfiler.class.getClassLoader()
			.getResourceAsStream(input);
			log.debug("reader=" + reader);
		return (EPackage) FHIRSerDeser.load(reader, Finals.SDS_FORMAT.ECORE);
	}

	EPackage copySpec(EPackage spec) {
		return (EPackage) EcoreUtil.copy(spec);
	}

	void clearClassifiers(EPackage pkg) {
		pkg.getEClassifiers().clear();
	}

	private EStructuralFeature copyFeature(EStructuralFeature original) {
		return (EStructuralFeature) EcoreUtil.copy(original);
	}

	public void applySnapshotElementToFeature(
		ElementDefinition snapshotElem,
		EStructuralFeature outFeature) {
		
		// --- Apply cardinality ---
		applyBounds(snapshotElem, outFeature);
		applySlice(snapshotElem, outFeature);

		// --- Add FHIR annotations ---
		EAnnotation fhirAnnotation = outFeature.getEAnnotation(HL7_FHIR_URL);
		if (fhirAnnotation == null) {
			fhirAnnotation = EcoreFactory.eINSTANCE.createEAnnotation();
			fhirAnnotation.setSource(HL7_FHIR_URL);
			outFeature.getEAnnotations().add(fhirAnnotation);
		}

		// mustSupport
		org.hl7.fhir.Boolean mustSupport = snapshotElem.getMustSupport();
		if (mustSupport != null) {
			if (Boolean.TRUE.equals(mustSupport.isValue())) {
				fhirAnnotation.getDetails().put("mustSupport", "true");
			}
		}

		// fixed[x]
		// Type fixed = snapshotElem.getFixed();
		// if (fixed != null) {
		// 	String value = fixed.toString();
		// 	fhirAnnotation.getDetails().put("fixed", value);
		// }

		// pattern[x]
		// Type pattern = snapshotElem.getPattern();
		// if (pattern != null) {
		// 	String value = pattern.toString();
		// 	fhirAnnotation.getDetails().put("pattern", value);
		// }

		// binding
		ElementDefinitionBinding binding = snapshotElem.getBinding();
		if (binding != null) {
			String valueSet = binding.getValueSet().getValue();
			if (valueSet != null) {
				fhirAnnotation.getDetails().put("binding.valueSet", valueSet);
			}

			Object strength = binding.getStrength(); // might be enum or string
			if (strength != null) {
				fhirAnnotation.getDetails().put("binding.strength", strength.toString());
			}
		}

		// --- Add documentation ---
		String doc = snapshotElem.getShort().getValue();
		if (doc == null) {
			doc = snapshotElem.getDefinition().getValue();
		}

		if (doc != null) {
			EAnnotation genModelAnnotation = outFeature.getEAnnotation(ECORE_GENMODEL_URL);
			if (genModelAnnotation == null) {
				genModelAnnotation = EcoreFactory.eINSTANCE.createEAnnotation();
				genModelAnnotation.setSource(ECORE_GENMODEL_URL);
				outFeature.getEAnnotations().add(genModelAnnotation);
			}

			genModelAnnotation.getDetails().put("documentation", doc);
		}
	}
   
	// public static void applyDifferentialUpdates(EPackage outputPackage, EList<ElementDefinition> differentialElements) {
    //     for (ElementDefinition diffElem : differentialElements) {
    //         String path = diffElem.getPath().getValue(); // e.g., "AdverseEvent.actuality"
    //         String[] pathParts = path.split("\\.");
    //         if (pathParts.length < 2) continue;

    //         String className = pathParts[0];
    //         String featureName = pathParts[1];

    //         EClassifier classifier = outputPackage.getEClassifier(className);
    //         if (!(classifier instanceof EClass)) {
    //             System.out.println("⚠️ Class not found: " + className);
    //             continue;
    //         }

    //         EClass eClass = (EClass) classifier;
    //         EStructuralFeature feature = eClass.getEStructuralFeature(featureName);
    //         if (feature == null) {
    //             System.out.println("⚠️ Feature not found: " + featureName + " in class " + className);
    //             continue;
    //         }

    //         // Example: Apply MustSupport as an annotation
    //         if (diffElem.getMustSupport().isValue()) {
    //             EAnnotation annotation = feature.getEAnnotation(HL7_FHIR_URL);
    //             if (annotation == null) {
    //                 annotation = EcoreFactory.eINSTANCE.createEAnnotation();
    //                 annotation.setSource("fhir");
    //                 feature.getEAnnotations().add(annotation);
    //             }
    //             annotation.getDetails().put("mustSupport", "true");
    //         }

	// 	// Search for any fixed[x] value using EMF reflection
	// 	EClass elemClass = diffElem.eClass();
	// 	for (EStructuralFeature f : elemClass.getEStructuralFeatures()) {
	// 		if (f.getName().startsWith("fixed")) {
	// 			Object fixedValue = diffElem.eGet(f);
	// 			if (fixedValue != null) {
	// 				String fixedStr = fixedValue.toString(); // or extract details depending on type

	// 				// Add as EAnnotation to your output feature
	// 				EAnnotation annotation = feature.getEAnnotation(HL7_FHIR_URL);
	// 				if (annotation == null) {
	// 					annotation = EcoreFactory.eINSTANCE.createEAnnotation();
	// 					annotation.setSource("fhir");
	// 					feature.getEAnnotations().add(annotation);
	// 				}

	// 				annotation.getDetails().put("fixed", fixedStr);
	// 				break; // Assume only one fixed[x] is set
	// 			}
	// 		}        
	// 	}
	// }
	// 	// Check if this ElementDefinition has a binding
	// 	ElementDefinitionBinding binding = diffElem.getBinding();
	// 	if (binding != null) {
	// 		String valueSetUrl = binding.getValueSet().getValue(); // likely a URI or Canonical type
	// 		BindingStrength strength = binding.getStrength(); // probably an enum

	// 		if (valueSetUrl != null || strength != null) {
	// 			EAnnotation annotation = feature.getEAnnotation(HL7_FHIR_URL);
	// 			if (annotation == null) {
	// 				annotation = EcoreFactory.eINSTANCE.createEAnnotation();
	// 				annotation.setSource("fhir");
	// 				feature.getEAnnotations().add(annotation);
	// 			}

	// 			if (valueSetUrl != null) {
	// 				annotation.getDetails().put("binding.valueSet", valueSetUrl);
	// 			}

	// 			if (strength != null) {
	// 				annotation.getDetails().put("binding.strength", strength.getName()); // or getLiteral()
	// 			}
	// 		}
	// 	}
	// }

////////////////////////////	
// 		// Check for a 'binding' feature in the differential ElementDefinition
// 		EStructuralFeature bindingFeature = diffElem.eClass().getEStructuralFeature("binding");

// 		if (bindingFeature != null) {
// 			Object bindingObj = diffElem.eGet(bindingFeature);
// 			if (bindingObj instanceof EObject) {
// 				EObject binding = (EObject) bindingObj;

// 				// Get valueSet and strength from the binding EObject
// 				EStructuralFeature valueSetFeature = binding.eClass().getEStructuralFeature("valueSet");
// 				EStructuralFeature strengthFeature = binding.eClass().getEStructuralFeature("strength");

// 				String valueSetUrl = null;
// 				String strengthCode = null;

// 				if (valueSetFeature != null) {
// 					Object value = binding.eGet(valueSetFeature);
// 					if (value != null) {
// 						valueSetUrl = value.toString();
// 					}
// 				}

// 				if (strengthFeature != null) {
// 					Object value = binding.eGet(strengthFeature);
// 					if (value != null) {
// 						strengthCode = value.toString();
// 					}
// 				}

// 				// Add annotation to the feature if either value is found
// 				if (valueSetUrl != null || strengthCode != null) {
// 					EAnnotation annotation = feature.getEAnnotation(HL7_FHIR_URL);
// 					if (annotation == null) {
// 						annotation = EcoreFactory.eINSTANCE.createEAnnotation();
// 						annotation.setSource("fhir");
// 						feature.getEAnnotations().add(annotation);
// 					}

// 					if (valueSetUrl != null) {
// 						annotation.getDetails().put("binding.valueSet", valueSetUrl);
// 					}
// 					if (strengthCode != null) {
// 						annotation.getDetails().put("binding.strength", strengthCode);
// 					}
// 				}
// 			}
// }
//	 }

	
// private void applySnapshotAndDifferential(
//     StructureDefinitionSnapshot snap,
//     StructureDefinitionDifferential diff,
//     EPackage spec,
//     EPackage out) {
//     // your earlier logic to populate `out` from `snap` and `diff`
// }

// private void writeOut(EPackage out) {
//     FHIRSerDeser.save(out, Finals.SDS_FORMAT.ECORE);
// }
	
	public static void main(String[] args) {
        try {
            AHRQProfiler app = new AHRQProfiler(args);
            log.info("Start==>");
			if (app.isHelp()) {
                app.printUsage();
                return;
            }
            app.run();
            log.info("<==Finish");
        } catch (CmdLineException e) {
            log.error("Soaping is wrong.", e);
        }
    }

	// public static OutputStream profileResource(EObject eObject) {
	// 	URI ecoreURI =  URI.createFileURI("data/fhir.ecore");
	// 	resource = resourceSet.getResource(ecoreURI, true);
	// 	ByteArrayOutputStream writer = null;
	// 	try {
	// 		writer = new ByteArrayOutputStream();
	// 		resource.save(System.out, Collections.EMPTY_MAP);
	// 		writer.close();
	// 	} catch (JsonProcessingException e) {
	// 		log.error("", e);
	// 	} catch (IOException e) {
	// 		log.error("", e);
	// 	}
	// 	return writer;

	// }

	// void loadParse() {
		
	// 	ResourceSet resourceSet = new ResourceSetImpl();
	// 	Resource fhirResource = resourceSet.getResource(URI.createFileURI("fhir.ecore"), true);
	// 	Resource profileResource = resourceSet.getResource(URI.createFileURI("custom_profile.ecore"), true);
		
	// 	EObject fhirRoot = fhirResource.getContents().get(0);
	// 	EObject profileRoot = profileResource.getContents().get(0);
	// }

	// void iterateElements() {
	// 	for (EClassifier classifier : fhirEPackage.getEClassifiers()) {
	// 		if (classifier instanceof EClass) {
	// 			EClass eClass = (EClass) classifier;
	// 			if (profileDefinesRestrictions(eClass)) {
	// 				applyProfileConstraints(eClass, profileEPackage);
	// 			}
	// 		}
	// 	}		
	// }

	// void merge() {
	// 	Resource newEcoreResource = resourceSet.createResource(URI.createFileURI("profiled_fhir.ecore"));
	// 	newEcoreResource.getContents().add(fhirRoot);
	// 	newEcoreResource.save(Collections.EMPTY_MAP);
	// }
}
