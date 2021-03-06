package analyser;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Map.Entry;

import es.EscapeStatus;
import handlers.JAssignStmtHandler;
import handlers.JIdentityStmtHandler;
import handlers.JInvokeStmtHandler;
import handlers.JReturnStmtHandler;
import handlers.JThrowStmtHandler;
import handlers.MonitorStmtHandler;
import ptg.Analysis;
import ptg.FlowSet;
import ptg.ObjectNode;
import ptg.ObjectType;
import ptg.PointsToGraph;
import soot.Body;
import soot.BodyTransformer;
import soot.PatchingChain;
import soot.Scene;
import soot.SootMethod;
import soot.Unit;
import soot.jimple.MonitorStmt;
import soot.jimple.internal.JAssignStmt;
import soot.jimple.internal.JGotoStmt;
import soot.jimple.internal.JIdentityStmt;
import soot.jimple.internal.JIfStmt;
import soot.jimple.internal.JInvokeStmt;
import soot.jimple.internal.JLookupSwitchStmt;
import soot.jimple.internal.JReturnStmt;
import soot.jimple.internal.JReturnVoidStmt;
import soot.jimple.internal.JTableSwitchStmt;
import soot.jimple.internal.JThrowStmt;
import soot.toolkits.graph.BriefUnitGraph;
import utils.getBCI;

public class StaticAnalyser extends BodyTransformer {
	public static HashMap<SootMethod,PointsToGraph> ptgs;
	public static HashMap<SootMethod,HashMap<ObjectNode,EscapeStatus>> summaries;
	public static LinkedHashMap<Body, Analysis> analysis;
	public StaticAnalyser() {
		super();
		analysis = new LinkedHashMap<>();
		ptgs = new HashMap<>();
		summaries = new HashMap<>();
	}


	@Override
	protected void internalTransform (Body body, String phasename, Map<String, String> options) {
		boolean verboseFlag = false;
//		if(body.getMethod().getName().equals("main")) verboseFlag = true;
		String path = Scene.v().getSootClassPath();
//		System.out.println(path);
//		System.out.println("Package:"+body.getMethod().getDeclaringClass().getJavaPackageName());
		Path p = Paths.get(path.substring(0, path.indexOf(":")) + "/" + body.getMethod().getDeclaringClass().toString() + ".res");
//		System.out.println(".res file path:"+p);
		HashMap<ObjectNode, EscapeStatus> summary = new HashMap<>();
		System.out.println("Method Name: "+ body.getMethod().getSignature() );
		
		PatchingChain<Unit> units = body.getUnits();

		// The flowSets
		Map<Unit, FlowSet> flowSets = new LinkedHashMap<>(units.size());

		BriefUnitGraph cfg = new BriefUnitGraph(body);

		LinkedHashSet<Unit> workList = new LinkedHashSet<Unit>();
		LinkedHashSet<Unit> workListNext = new LinkedHashSet<Unit>(units);
		LinkedHashSet<Unit> temp = null;
		// initialize the flow sets with empty sets
		for(Unit u : units) {
			flowSets.put(u, new FlowSet());
		}
		
		int i=0;
		while(!workListNext.isEmpty()){
			if(verboseFlag) {
				System.out.println("Loop "+i);
				System.out.println("Worklist:");
				workList.forEach(w -> System.out.println(w));
				System.out.println("WorkListNext:");
				workListNext.forEach(w -> System.out.println(w));
			}
			/*
			 * Swap workList and workListNext
			 */
//			if(body.getMethod().toString().contains("visitMaxs")) System.out.println("[Loop:"+i+"]:"+printList(workListNext));
			temp = workList; workList = workListNext; workListNext = temp;
			workListNext.clear();
			
			/*
			 * Main Work Loop:
			 * for each Unit u in workList
			 * 1. inNew = union(out[predecessors])
			 * 2. outNew = apply(u, inNew)
			 * 3. if(outNew != out[u]):
			 * 		add successors to workListNext
			 * 		out[u] = outNew
			 */
			ObjectNode scrutiny = new ObjectNode(17, ObjectType.internal);
			Iterator<Unit> iterator = workList.iterator();
			while(iterator.hasNext()) {
				Unit u = iterator.next();
				iterator.remove();
//				int bci = -2;
//				try {
//					bci = utils.getBCI.get(u);
//				} catch (Exception e) {
					// do nothing
//				}
//				if(u.toString().contains("$r9 = \"java/lang/Throwable\"")) {
//					if(body.getMethod().toString().contains("visitMaxs")) System.out.println("[Loop:"+i+"]I have reached that wretched line:"+u.toString()+" in method "+body.getMethod().toString());
//					System.out.println(body);
//					throw new IllegalArgumentException("That horrible line!");
//				}
				workListNext.remove(u);
				FlowSet flowSet = flowSets.get(u);
				/*
				 * 1. inNew = union(out[predecessors])
				 */
				PointsToGraph inNew = new PointsToGraph();
				for(Unit pred : cfg.getPredsOf(u)) {
					inNew.union(flowSets.get(pred).getOut());
				}
				if(i>75 && body.getMethod().toString().contains("visitMaxs")) {
//					System.out.println("[BCI:"+getBCI.get(u)+", unit:"+u.toString()+"]" );
//					System.out.println("InNew:"+inNew.toString());
				}
//				if(bci == 135) {
//					System.out.println("[BCI 135]");
//					System.out.println("inNew:"+inNew);
//					System.out.println("old:"+flowSets.get(u));
//				}
				if(inNew.equals(flowSet.getIn()) && !inNew.isEmpty() ) {
//					if(body.getMethod().toString().contains("visitMaxs")) System.out.println("[Loop:"+i+"] Removing successors of:"+u);
					workListNext.removeAll(cfg.getSuccsOf(u));
					continue;
				}
				flowSet.setIn(inNew);
				
				/*
				 * 2. outNew = apply(u, inNew)
				 */
				PointsToGraph outNew = new PointsToGraph(inNew);
				try {
					apply(u, outNew, summary);
				}				
				catch(Exception e) {
					String s = "->*** Error at: "+u.toString()+" of "+body.getMethod().getBytecodeSignature();
//					System.out.println(body);
					System.out.println(s);
//					System.out.println(workList);
					throw e;
				}
//				if(bci == 135) {
//					System.out.println("outNew:"+outNew);
//				}
				if(verboseFlag && summary.containsKey(scrutiny)) {
					System.out.println("after "+u.toString()+" summary["+scrutiny.toString()+"] = "+summary.get(scrutiny).toString());
				}
				/*
				 * 3. if(outNew != out[u]):
				 * 		add successors to workList
				 * 		out[u] = outNew
				 */
				if(!outNew.equals(flowSet.getOut())) {
//					if(i>75 && body.getMethod().toString().contains("visitMaxs"))System.out.println("OutNew is new:"+outNew.toString());
					workListNext.addAll(cfg.getSuccsOf(u));
					flowSet.setOut(outNew);
				} else {
//					if(i>75 && body.getMethod().toString().contains("visitMaxs"))System.out.println("OutOld (remains same):"+flowSet.getOut().toString());
				}
			}
			i+=1;
			
		}
//		Analysis currentAnalysis = new Analysis(flowSets, summary);
//		analysis.put(body, currentAnalysis);
//		String output = body.getMethod().getSignature()+"\n"+currentAnalysis.toString();
		
		/*
		 * 
		
		try {
			Files.write(p, output.getBytes(StandardCharsets.UTF_8), 
					Files.exists(p) ? StandardOpenOption.APPEND : StandardOpenOption.CREATE);
		} catch (IOException e) {
			e.printStackTrace();
		}
		*/
		Iterator<Entry<Unit, FlowSet>> iterator = flowSets.entrySet().iterator();
		Entry<Unit, FlowSet> elem = iterator.next();
		while(iterator.hasNext()) elem = iterator.next();
		PointsToGraph ptg = elem.getValue().getOut();
		ptgs.put(body.getMethod(), ptg);
		summaries.put(body.getMethod(), summary);
	}
	
	/*
	 * apply will apply the changes of the current unit on the provided
	 * points-to graph. Note that this will NOT make a copy to make
	 * changes on.
	 */

	public void apply(Unit u, PointsToGraph ptg, HashMap<ObjectNode, EscapeStatus> summary){
		if(u instanceof JAssignStmt) {
			JAssignStmtHandler.handle(u, ptg, summary);
		} else if(u instanceof JIdentityStmt) {
			JIdentityStmtHandler.handle(u, ptg, summary);
		} else if(u instanceof JInvokeStmt) {
			JInvokeStmtHandler.handle(u, ptg, summary);
		} else if(u instanceof JReturnVoidStmt) {
			// Nothing to do here!
		} else if(u instanceof JReturnStmt) {
			JReturnStmtHandler.handle(u, ptg, summary);
		} else if(u instanceof JThrowStmt) {
			JThrowStmtHandler.handle(u, ptg, summary);
		} else if(u instanceof MonitorStmt) {
			MonitorStmtHandler.handle(u, ptg, summary);
		} else if(u instanceof JIfStmt || u instanceof JGotoStmt ||
				u instanceof JTableSwitchStmt || u instanceof JLookupSwitchStmt) {
		} else {
			System.out.println("Unidentified class: "+ u.getClass() + " with BCI "+ utils.getBCI.get(u) +" at:\n"+u);
			throw new IllegalArgumentException(u.toString());
		}
	}
	
	public void printAnalysis() {
		for(Map.Entry<Body, Analysis> entry : analysis.entrySet()) {
			System.out.println("Class: "+entry.getKey().getMethod().getDeclaringClass());
			System.out.println("Method: "+entry.getKey().getMethod().getName());
			System.out.println("Analysis:\n"+entry.getValue());
		}
	}
	
	private String printList(LinkedHashSet<Unit> l) {
		StringBuilder sb = new StringBuilder();
		sb.append("[");
		l.forEach(u -> {
			int ref = -1;
			try {
				ref = utils.getBCI.get(u);
			} catch (Exception e) {
				// do nothing
				System.out.println("[StaticAnalyser.printList] [Apology] I was trying to print list of BCI. Sorry!");
			}
			sb.append(ref);
			sb.append(",");
		});
		sb.append("]");
		return sb.toString();
	}

}
