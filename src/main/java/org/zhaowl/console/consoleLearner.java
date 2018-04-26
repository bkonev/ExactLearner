package org.zhaowl.console;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.coode.owlapi.manchesterowlsyntax.ManchesterOWLSyntaxOntologyFormat;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.OWLObjectRenderer;
import org.semanticweb.owlapi.model.*;
import org.zhaowl.engine.ELEngine;
import org.zhaowl.learner.ELLearner;
import org.zhaowl.oracle.ELOracle;
import org.zhaowl.utils.Metrics;
import uk.ac.manchester.cs.owl.owlapi.mansyntaxrenderer.ManchesterOWLSyntaxOWLObjectRendererImpl;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

//import javax.swing.JOptionPane;

public class consoleLearner {

	private static final double SATURATION_BOUND = 0.5;

	private String filePath;

	// ############# Game variables Start ######################

	// #########################################################

	// ############# OWL variables Start ######################

	private static final OWLOntologyManager myManager = OWLManager.createOWLOntologyManager();
	private final OWLObjectRenderer myRenderer = new ManchesterOWLSyntaxOWLObjectRendererImpl();
	private final Metrics myMetrics = new Metrics(myRenderer);

	private Set<OWLAxiom> axiomsT = null;

	private String ontologyFolder = null;
	private String ontologyName = null;
	private File hypoFile = null;

	private ArrayList<String> roles = new ArrayList<>();

	private String ontologyFolderH = null;

	private OWLSubClassOfAxiom lastCE = null;
	private OWLClassExpression lastExpression = null;
	private OWLClass lastName = null;
	private OWLOntology targetOntology = null;
	private OWLOntology hypothesisOntology = null;

	private ELEngine elQueryEngineForT = null;
	private ELEngine elQueryEngineForH = null;

	private ELLearner elLearner = null;
	private ELOracle elOracle = null;

	// ############# OWL variables Start ######################

	// #########################################################

	// ############# Oracle and Learner skills Start ######################

	private boolean oracleSaturate;
	private boolean oracleMerge;
	private boolean oracleBranch;
	private boolean oracleUnsaturate;

	private boolean learnerSat;
	private boolean learnerMerge;
	private boolean learnerDecompL;
	private boolean learnerUnsat;
	private boolean learnerBranch;
	private boolean learnerDecompR;

	// ############# Oracle and Learner skills END ######################

	public static void main(String[] args) {
		Logger.getRootLogger().setLevel(Level.OFF);
		consoleLearner maker = new consoleLearner();
		maker.doIt(args);

	}

	public void doIt(String[] args) {

		try {
			// targetOntology from parameters
			filePath = args[0];

			// setLearnerSkills
			setLearnerSkills(args);

			// setLearnerSkills
			setOracleSkills(args);
			

			try {
				// load targetOntology
				setupOntologies();

				elQueryEngineForT = new ELEngine(targetOntology);
				elQueryEngineForH = new ELEngine(hypothesisOntology);

				elLearner = new ELLearner(elQueryEngineForT, elQueryEngineForH, myMetrics);
				elOracle = new ELOracle(elQueryEngineForT, elQueryEngineForH, myMetrics);

				long timeStart = System.currentTimeMillis();
				runLearner(elQueryEngineForT, elQueryEngineForH);
				long timeEnd = System.currentTimeMillis();
				System.out.println("Total time (ms): " + (timeEnd - timeStart));
				System.out.println("Total membership queries: " + myMetrics.getMembCount());
				System.out.println("Total equivalence queries: " + myMetrics.getEquivCount());
				System.out.println("Target TBox logical axioms: " + axiomsT.size());
				//////////////////////////////////////////////////////////////////////
				System.out.println("Total left decompositions: " + elLearner.getNumberLeftDecomposition());
				System.out.println("Total right decompositions: " + elLearner.getNumberRightDecomposition());
				System.out.println("Total mergings: " + elLearner.getNumberMerging());
				System.out.println("Total branchings: " + elLearner.getNumberBranching());
				System.out.println("Total saturations: " + elLearner.getNumberSaturations());
				System.out.println("Total unsaturations: " + elLearner.getNumberUnsaturations());
				saveOWLFile(hypothesisOntology, hypoFile);

				myMetrics.showCIT(axiomsT, true);

				System.out.println("Hypothesis TBox logical axioms: " + hypothesisOntology.getAxioms().size());
				myMetrics.showCIT(hypothesisOntology.getAxioms(), false);
				elQueryEngineForH.disposeOfReasoner();
				elQueryEngineForT.disposeOfReasoner();
				myManager.removeOntology(hypothesisOntology);
				myManager.removeOntology(targetOntology);

			} catch (Throwable e) {
				e.printStackTrace();
				System.out.println("error in runLearner call ----- " + e);
			}

		} catch (Throwable e) {
			// TODO Auto-generated catch block
			System.out.println("error in doIt --- " + e);
		} finally {
			elQueryEngineForH.disposeOfReasoner();
			elQueryEngineForT.disposeOfReasoner();
		}

	}

	private void setOracleSkills(String[] args) {
		oracleMerge = args[7].equals("t");

		oracleSaturate = args[8].equals("t");

		oracleBranch = args[9].equals("t");

		oracleUnsaturate = args[10].equals("t");
	}

	private void setLearnerSkills(String[] args) {

		learnerDecompL = args[1].equals("t");

		learnerBranch = args[2].equals("t");

		learnerUnsat = args[3].equals("t");

		learnerDecompR = args[4].equals("t");

		learnerMerge = args[5].equals("t");

		learnerSat = args[6].equals("t");

	}

	private void runLearner(ELEngine elQueryEngineForT, ELEngine elQueryEngineForH) throws Throwable {
		while (!equivalenceQuery()) {
			myMetrics.setEquivCount(myMetrics.getMembCount() + 1);
			lastCE = getCounterExample(elQueryEngineForT, elQueryEngineForH);
			  
			OWLSubClassOfAxiom counterexample = lastCE;
			OWLClassExpression left = counterexample.getSubClass();
			OWLClassExpression right = counterexample.getSuperClass();
			lastCE = elLearner.decompose(left, right);
			 
			if (canTransformELrhs()) {
				  
				lastCE = computeEssentialRightCounterexample();
				 
				// TODO
				// if there is a concept name in H ...
				// if()
				// siblingmerge
			} else if (canTransformELlhs()) {
				  
				lastCE = computeEssentialLeftCounterexample();
				 
			}  
		 
			addHypothesis(lastCE);
		}
		victory();
		lastCE = null;
	}

	private void addHypothesis(OWLAxiom addedAxiom) {

		myManager.addAxiom(hypothesisOntology, addedAxiom);
		System.out.println(addedAxiom);
		minimiseHypothesis();

	}

	private void saveOWLFile(OWLOntology ontology, File file) throws Exception {

		OWLOntologyFormat format = myManager.getOntologyFormat(ontology);
		ManchesterOWLSyntaxOntologyFormat manSyntaxFormat = new ManchesterOWLSyntaxOntologyFormat();
		if (format.isPrefixOWLOntologyFormat()) {
			// need to remove prefixes
			manSyntaxFormat.clearPrefixes();
		}
		// format = null;
		myManager.saveOntology(ontology, manSyntaxFormat, IRI.create(file.toURI()));
	}

	private void minimiseHypothesis() {

		Set<OWLAxiom> tmpaxiomsH = elQueryEngineForH.getOntology().getAxioms();
		Iterator<OWLAxiom> ineratorMinH = tmpaxiomsH.iterator();
		Set<OWLAxiom> checkedAxiomsSet = new HashSet<>();

		if (tmpaxiomsH.size() > 1) {
			while (ineratorMinH.hasNext()) {
				OWLAxiom checkedAxiom = ineratorMinH.next();
				if (!checkedAxiomsSet.contains(checkedAxiom)) {
					checkedAxiomsSet.add(checkedAxiom);

					RemoveAxiom removedAxiom = new RemoveAxiom(elQueryEngineForH.getOntology(), checkedAxiom);
					elQueryEngineForH.applyChange(removedAxiom);

					Boolean queryAns = elQueryEngineForH.entailed(checkedAxiom);

					if (!queryAns) {
						// put it back
						AddAxiom addAxiomtoH = new AddAxiom(hypothesisOntology, checkedAxiom);
						elQueryEngineForH.applyChange(addAxiomtoH);
					}
				}
			}
		}

	}

	private Boolean canTransformELrhs() {

		OWLSubClassOfAxiom counterexample = lastCE;
		OWLClassExpression left = counterexample.getSubClass();
		OWLClassExpression right = counterexample.getSuperClass();
		for (OWLClass cl1 : left.getClassesInSignature()) {
			if (elOracle.isCounterExample(cl1, right)) {
				lastCE = elQueryEngineForT.getSubClassAxiom(cl1, right);
				lastExpression = right;
				lastName = cl1;
				return true;
			}
		}
		return false;
	}

	private Boolean canTransformELlhs() {
		OWLSubClassOfAxiom counterexample = lastCE;
		OWLClassExpression left = counterexample.getSubClass();
		OWLClassExpression right = counterexample.getSuperClass();
		for (OWLClass cl1 : right.getClassesInSignature()) {
			if (elOracle.isCounterExample(left, cl1)) {
				lastCE = elQueryEngineForT.getSubClassAxiom(left, cl1);
				lastExpression = left;
				lastName = cl1;
				return true;
			}
		}
		return false;
	}

	private OWLSubClassOfAxiom computeEssentialLeftCounterexample() throws Exception {
		OWLSubClassOfAxiom axiom = lastCE;
		OWLSubClassOfAxiom counterexample = axiom;
		OWLClassExpression left = counterexample.getSubClass();
		OWLClass right = (OWLClass) counterexample.getSuperClass();

		if (learnerDecompL) {
			axiom = elLearner.decomposeLeft(lastExpression, lastName);
			counterexample = axiom;
			left = counterexample.getSubClass();
			right = (OWLClass) counterexample.getSuperClass();
		}

		if (learnerBranch) {
			axiom = elLearner.branchLeft(left, right);
			counterexample = axiom;
			left = counterexample.getSubClass();
			right = (OWLClass) counterexample.getSuperClass();
		}

		if (learnerUnsat) {
			axiom = elLearner.unsaturateLeft(left, right);
		}

		return axiom;
	}

	private OWLSubClassOfAxiom computeEssentialRightCounterexample() throws Exception {
		OWLSubClassOfAxiom axiom = lastCE;
		OWLSubClassOfAxiom counterexample = axiom;
		OWLClass left = (OWLClass) counterexample.getSubClass();
		OWLClassExpression right = counterexample.getSuperClass();
		 
		if (learnerDecompR) {
			axiom = elLearner.decomposeRight(lastName, lastExpression);
			counterexample = axiom;
			left = (OWLClass) counterexample.getSubClass();
			right = counterexample.getSuperClass();
		}
		 
		if (learnerMerge) {
			axiom = elLearner.mergeRight(left, right);
			counterexample = axiom;
			left = (OWLClass) counterexample.getSubClass();
			right = counterexample.getSuperClass();
		}
		 
		if (learnerSat) {
			axiom = elLearner.saturateRight(left, right);
		} 
		return axiom;
	}

	private void victory() {

		System.out.println("Ontology learned successfully!");
		System.out.println("You dun did it!!!");

		axiomsT = new HashSet<>();
		for (OWLAxiom axe : targetOntology.getAxioms())
			if (!axe.toString().contains("Thing") && axe.isOfType(AxiomType.SUBCLASS_OF)
					|| axe.isOfType(AxiomType.EQUIVALENT_CLASSES))
				axiomsT.add(axe);
	}

	private void setupOntologies() {

		try {

			System.out.println("Trying to load targetOntology");
			targetOntology = myManager.loadOntologyFromOntologyDocument(new File(filePath));

			axiomsT = new HashSet<>();
			for (OWLAxiom axe : targetOntology.getAxioms())
				if (!axe.toString().contains("Thing") && axe.isOfType(AxiomType.SUBCLASS_OF)
						|| axe.isOfType(AxiomType.EQUIVALENT_CLASSES))
					axiomsT.add(axe);

			lastCE = null;

			// transfer Origin targetOntology to ManchesterOWLSyntaxOntologyFormat
			OWLOntologyFormat format = myManager.getOntologyFormat(targetOntology);
			ManchesterOWLSyntaxOntologyFormat manSyntaxFormat = new ManchesterOWLSyntaxOntologyFormat();
			if (format.isPrefixOWLOntologyFormat()) {
				manSyntaxFormat.copyPrefixesFrom(format.asPrefixOWLOntologyFormat());
			}

			// create personalized names for targetOntology
			ontologyFolderH = "src/main/resources/tmp/";
			ontologyFolder = "src/main/resources/tmp/";
			ontologyName = "";
			getOntologyName();

			// save ontologies
			File newFile = new File(ontologyFolder);
			hypoFile = new File(ontologyFolderH);
			// save owl file as a new file in different location
			if (newFile.exists()) {
				newFile.delete();
			}
			newFile.createNewFile();
			myManager.saveOntology(targetOntology, manSyntaxFormat, IRI.create(newFile.toURI()));

			// Create OWL Ontology Manager for hypothesis and load hypothesis file
			if (hypoFile.exists()) {
				hypoFile.delete();
			}
			hypoFile.createNewFile();

			hypothesisOntology = myManager.loadOntologyFromOntologyDocument(hypoFile);

			System.out.println(targetOntology);
			System.out.println("Loaded successfully.");
			System.out.println();

			ArrayList<String> concepts = myMetrics.getSuggestionNames("concept", newFile);
			roles = myMetrics.getSuggestionNames("role", newFile);

			System.out.println("Total number of concepts is: " + concepts.size());

			System.out.flush();
		} catch (OWLOntologyCreationException e) {
			System.out.println("Could not load targetOntology: " + e.getMessage());
		} catch (OWLException | IOException e) {
			e.printStackTrace();
		}

	}

	private void getOntologyName() {

		int con = 0;
		for (int i = 0; i < targetOntology.getOntologyID().toString().length(); i++)
			if (targetOntology.getOntologyID().toString().charAt(i) == '/')
				con = i;
		ontologyName = targetOntology.getOntologyID().toString().substring(con + 1,
				targetOntology.getOntologyID().toString().length());
		ontologyName = ontologyName.substring(0, ontologyName.length() - 3);
		if (!ontologyName.contains(".owl"))
			ontologyName = ontologyName + ".owl";
		ontologyFolder += ontologyName;
		ontologyFolderH += "hypo_" + ontologyName;
	}

	private Boolean equivalenceQuery() {

		return elQueryEngineForH.entailed(axiomsT);
	}

	public OWLSubClassOfAxiom getCounterExample(ELEngine elQueryEngineForT, ELEngine elQueryEngineForH)
			throws Exception {
		
		for (OWLAxiom selectedAxiom : axiomsT) {
			selectedAxiom.getAxiomType();
		
			// first get CounterExample from an axiom with the type SUBCLASS_OF
			if (selectedAxiom.isOfType(AxiomType.SUBCLASS_OF)) {
				if (!elQueryEngineForH.entailed(selectedAxiom)) {
					 
					OWLSubClassOfAxiom counterexample = (OWLSubClassOfAxiom) selectedAxiom;
			
					return getCounterExampleSubClassOf(elQueryEngineForT,elQueryEngineForH,counterexample);	
				}
			}
			if (selectedAxiom.isOfType(AxiomType.EQUIVALENT_CLASSES)) {
				 

					OWLEquivalentClassesAxiom equivCounterexample = (OWLEquivalentClassesAxiom) selectedAxiom;
					Set<OWLSubClassOfAxiom> eqsubclassaxioms = equivCounterexample.asOWLSubClassOfAxioms();
					Iterator<OWLSubClassOfAxiom> iterator = eqsubclassaxioms.iterator();

					while (iterator.hasNext()) {
						OWLSubClassOfAxiom subClassAxiom = iterator.next();
						if (!elQueryEngineForH.entailed(subClassAxiom)) {
							 
							return getCounterExampleSubClassOf(elQueryEngineForT,elQueryEngineForH,subClassAxiom);	
						}
					}
				 	
			}
		}
		throw new Exception("No more counterexamples");
	}

			public OWLSubClassOfAxiom getCounterExampleSubClassOf(ELEngine elQueryEngineForT, ELEngine elQueryEngineForH,OWLSubClassOfAxiom counterexample)
					throws Exception {
				OWLSubClassOfAxiom newCounterexampleAxiom = counterexample;
				OWLClassExpression left = counterexample.getSubClass();
				OWLClassExpression right = counterexample.getSuperClass();
		 
					
					 

							if (oracleMerge) {
								newCounterexampleAxiom = elOracle.mergeLeft(left, right);
								left = ((OWLSubClassOfAxiom) newCounterexampleAxiom).getSubClass();
								right = ((OWLSubClassOfAxiom) newCounterexampleAxiom).getSuperClass();
							}
							if (oracleSaturate) {
								newCounterexampleAxiom = elOracle.saturateLeft(left, right, SATURATION_BOUND);
								left = ((OWLSubClassOfAxiom) newCounterexampleAxiom).getSubClass();
								right = ((OWLSubClassOfAxiom) newCounterexampleAxiom).getSuperClass();
							}

							if (oracleBranch) {
								newCounterexampleAxiom = elOracle.branchRight(left, right);
								left = ((OWLSubClassOfAxiom) newCounterexampleAxiom).getSubClass();
								right = ((OWLSubClassOfAxiom) newCounterexampleAxiom).getSuperClass();
							}
							if (oracleUnsaturate) {
								newCounterexampleAxiom = elOracle.unsaturateRight(left, right);
							}
						 
					 
					 
				 
				return newCounterexampleAxiom;
			}		
}
