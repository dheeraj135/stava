package resolver;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;

import es.*;
import ptg.ObjectNode;
import ptg.ObjectType;
import ptg.PointsToGraph;
import ptg.RetLocal;
import soot.SootField;
import soot.SootMethod;

public class SummaryResolver {
	public HashMap<SootMethod, HashMap<ObjectNode, EscapeStatus>> existingSummaries, solvedSummaries;
	HashMap<SootMethod, HashMap<ObjectNode,ResolutionStatus>> resolutionStatus;
	HashMap<SootMethod, PointsToGraph> ptgs;

	private void init(HashMap<SootMethod, HashMap<ObjectNode, EscapeStatus>> existingSummaries) {
		this.existingSummaries = existingSummaries;
		resolutionStatus = new HashMap<SootMethod, HashMap<ObjectNode,ResolutionStatus>>();
		this.solvedSummaries = new HashMap<>();
		existingSummaries.forEach((body, map) -> {
			HashMap<ObjectNode,ResolutionStatus> q = new HashMap<>();
			map.forEach((obj, es) -> q.put(obj, ResolutionStatus.UnAttempted));
			resolutionStatus.put(body, q);
			this.solvedSummaries.put(body, new HashMap<>());
		});
	
	}

	public void resolve(HashMap<SootMethod, HashMap<ObjectNode, EscapeStatus>> existingSummaries,
			HashMap<SootMethod, PointsToGraph> ptgs) {
		init(existingSummaries);
		this.ptgs = ptgs;
		existingSummaries.forEach((method,summary) -> {
//			System.out.println("--- <"+method.toString()+"> ---");
			summary.forEach((obj, es) -> {
//				System.out.println("normal trigger outOfContextSolve on: "+method.toString()+", "+obj.toString());
				outOfContextSolve(obj, method);
			});
//			System.out.println("--- </"+method.toString()+"> ---");
		});
//		printResults();
	}

	private boolean prelimCheck(ObjectNode obj, SootMethod method) {
		ResolutionStatus s = resolutionStatus.get(method).get(obj);
		if(s==ResolutionStatus.Resolved || s==ResolutionStatus.UnResolved || s==ResolutionStatus.CallerOnly) return true;
		return false;
	}
	
	private void outOfContextSolve(ObjectNode obj, SootMethod method) {
		if(prelimCheck(obj, method)) {
//			System.out.println(obj.toString()+" has status " + resolutionStatus.get(method).get(obj).toString());
			return;
		} 
		resolutionStatus.get(method).put(obj, ResolutionStatus.InProgress);
		EscapeStatus es = this.existingSummaries.get(method).get(obj);
		if(es.containsNoEscape() || es.doesEscape()) {
//			System.out.println(obj.toString() + " of "+ method.toString() + " is already resolved to "+ es.toString());
			resolutionStatus.get(method).put(obj, ResolutionStatus.Resolved);
			this.solvedSummaries.get(method).put(obj, es);
			return;
		}
		Iterator<EscapeState> it = es.getStatus().iterator();
		es = new EscapeStatus();
		while(it.hasNext()) {
			ConditionalValue cv = (ConditionalValue)it.next();
//			System.out.println("trigger helper on: "+cv.toString()+", of: "+obj.toString()+", of: "+method.toString()+" out of context.");
//			EscapeState e = recursiveResolve(cv, obj, method, null, null);
			EscapeState e = resolutionHelper(cv, obj, method);
//			System.out.println(cv.toString() + " of "+obj.toString()+" of "+method.toString()+" resolves to "+ e.toString());
			if(e == Escape.getInstance()) {es.setEscape(); break;}
			else if(e instanceof ConditionalValue) es.addEscapeState(e);
			else if(e == NoEscape.getInstance()); // Nothing to do here!
		}
		solvedSummaries.get(method).put(obj, es);
		resolutionStatus.get(method).put(obj, 
				es.containsCV()? 
						(es.isCallerOnly()? 
								ResolutionStatus.CallerOnly
								:ResolutionStatus.UnResolved)
						:ResolutionStatus.Resolved);
//		System.out.println(obj.toString()+" of "+method.toString()+" resolves to "+es.toString()+" with status "+resolutionStatus.get(method).get(obj));
	}
	
	private EscapeState resolutionHelper(ConditionalValue cv, ObjectNode obj, SootMethod method) {
		if(cv.getMethod()!=null) {
			SootMethod m = cv.getMethod();
			if(m.isJavaLibraryMethod()) {
				if(libMethodCheck(m)) return NoEscape.getInstance();
				else return cv;
			}
			Iterator<ObjectNode> i = getObjs(cv).iterator(); // these objects belong to cv.getmethod()
			EscapeStatus temp = new EscapeStatus();
			while(i.hasNext()) {
				ObjectNode o = i.next();
				ResolutionStatus s = resolutionStatus.get(m).get(o);
				if(s==ResolutionStatus.UnAttempted) {
//					System.out.println("helper trigger outofcontextsolve on "+o.toString()+" of "+m.toString());
					outOfContextSolve(o, m);
				}
				s = resolutionStatus.get(m).get(o);
				if(s==ResolutionStatus.Resolved) {
					EscapeStatus e = solvedSummaries.get(m).get(o);
					if(e.doesEscape()) return Escape.getInstance();
				} else if(s==ResolutionStatus.UnResolved) temp.addEscapeState(cv);
				else if(s==ResolutionStatus.InProgress); // Nothing to do here!
				else if(s==ResolutionStatus.CallerOnly) {
//					System.out.print("helper trigger callersolve on "+o.toString()+" of "+m.toString());
//					System.out.println(" context:"+obj.toString()+" of "+method.toString());
					EscapeState e = callerSolve(cv, o, m, obj, method);
					if(e instanceof Escape) return Escape.getInstance();
					else if(e instanceof ConditionalValue) temp.addEscapeState(cv);
				}
			}
			if(temp.containsCV()) return cv; // as it is unresolved
			else return NoEscape.getInstance();
		} else {
			// conditional value involving caller
			// method is caller!
			// may be of the form <caller, <arg,0>.f.g>
			return cv; // case where this is called out of context
		}
	}

	private EscapeState callerSolve(ConditionalValue cv, ObjectNode o, SootMethod m, ObjectNode callerObj,
			SootMethod callerMethod) {
		if(callerObj==null || callerMethod==null) return cv;
		if(this.resolutionStatus.get(m).get(o)!=ResolutionStatus.CallerOnly) {
			throw new IllegalArgumentException(o.toString()+" of "+m.toString()+" is not in caller only mode!");
		}
		// object o here is of CallerOnly status
		// cv belongs to callerObj of callerMethod
		// cv of the form <callerMethod.getMethod(), <parameter,0>.f.g> or <callerMethod.getMethod(), <returnValue,0>.f.g>
		// fully solve o in the context of callerObj, callerMethod
		EscapeStatus higherTemp = new EscapeStatus();
		EscapeStatus es = solvedSummaries.get(m).get(o);
		Iterator<EscapeState> it = es.getStatus().iterator();
		while(it.hasNext()) {
			ConditionalValue cvv = (ConditionalValue)it.next();
			int depth = cvv.getDepth();
			for (int i = 0; i <= depth; i++) {
				EscapeStatus temp = new EscapeStatus();
				Iterator<ObjectNode> it1 = getRelevantObjects(cvv, callerMethod, m, i).iterator();
				while(it1.hasNext()) {
					ObjectNode object = it1.next();
					ResolutionStatus s = resolutionStatus.get(callerMethod).get(object);
					if(s==ResolutionStatus.UnAttempted) {
//						System.out.print("callersolve trigger outofcontextsolve on "+o.toString()+" of "+m.toString());						
						outOfContextSolve(object, callerMethod);
					} 
					s = resolutionStatus.get(callerMethod).get(object);
					if(s==ResolutionStatus.Resolved) {
//						System.out.println("[callersolve]"+o.toString()+"is resolved to escape. Returning.");
						if(solvedSummaries.get(callerMethod).get(object).doesEscape()) return Escape.getInstance();
					} else if(s==ResolutionStatus.UnResolved) {
						temp.addEscapeState(cvv);
					} else if(s==ResolutionStatus.InProgress) {
//						System.out.println("[callersolve]"+o.toString()+"is inProgress. Continuing.");						
						// imples No Escape
					} else if(s==ResolutionStatus.CallerOnly) {
						// implies escape
//						System.out.println("[callersolve]"+o.toString()+"depends on it's caller. This is Escape. Returning.");
						return Escape.getInstance();
					}
				}
				if(temp.containsCV()) higherTemp.addEscapeState(cvv);
			}
			if(higherTemp.containsCV()) return cv;
			else return NoEscape.getInstance();
		}
//		Iterator<ObjectNode> = getRelevantObjectsFromCaller()
		return null;
	}

	private Iterable<ObjectNode> getObjs(ConditionalValue cv) {
		Iterable<ObjectNode> _ret = new LinkedHashSet<ObjectNode>();
		Collection<ObjectNode> c = (Collection<ObjectNode>) _ret;
		// <m, <parameter,0>.f.g> or <m,<returnValue,0>.f.g>
		LinkedList<ObjectNode> workList = new LinkedList<ObjectNode>();
		PointsToGraph ptg;
		ptg = this.ptgs.get(cv.getMethod());			
		if(ptg == null ) {
//			System.out.println("the method of "+ cv.toString() + " doesn't have a ptg defined!");
			return new LinkedList<ObjectNode>();
//			throw new IllegalArgumentException("the method of "+ cv.toString() + " doesn't have a ptg defined!");
		}
		if(cv.object.equals(new ObjectNode(0, ObjectType.returnValue))) {
			c.addAll(ptg.vars.get(RetLocal.getInstance()));
		} else {
			c.add(cv.object);
		}
		workList.addAll(c);
		LinkedList<ObjectNode> temp;
		LinkedList<ObjectNode> workListNext = new LinkedList<ObjectNode>();
		if(cv.fieldList!=null) {
			Iterator<SootField> i = cv.fieldList.iterator();
			while(i.hasNext()) {
				SootField f = i.next();
				Iterator<ObjectNode> itr = workList.iterator();
				while(itr.hasNext()) {
					ObjectNode o = itr.next();
					if(ptg.fields.containsKey(o) && ptg.fields.get(o).containsKey(f)) {
						ptg.fields.get(o).get(f).forEach(obj -> {
							if(!c.contains(obj)) c.add(obj);
						});
						workListNext.addAll(ptg.fields.get(o).get(f));
					}
				}
				workList.clear();
				temp = workListNext; workListNext = workList; workList = temp;
			}			
		}
		return _ret;
	}

	private Iterable<ObjectNode> getRelevantObjects(ConditionalValue cv, SootMethod callerMethod, SootMethod calleeMethod, int depth) {
		Iterable<ObjectNode> list = new LinkedHashSet<>();
		// cv can be of the form
		// <caller, <arg, 0>.f.g.h>
		// <caller, <returnValue, 0>.f.g.h>
		ObjectType t = null;
		if(cv.object.type==ObjectType.argument) {
			t = ObjectType.parameter;
		} else if(cv.object.type==ObjectType.returnValue) {
			t = ObjectType.returnValue;
		} else {
			throw new IllegalArgumentException("Invalid Objectype in cv:"+cv.toString());
		}
		ObjectNode comparison = new ObjectNode(cv.object.ref, t);
		ConditionalValue desired = new ConditionalValue(calleeMethod, comparison, (cv.fieldList==null)?null:cv.fieldList.subList(0, depth), new Boolean(true));
		existingSummaries.get(callerMethod).forEach((object, es) -> {
			es.getStatus().forEach(e -> {
				if(e instanceof ConditionalValue) {
					if(desired.compareAtDepth((ConditionalValue)e, depth))
						((LinkedHashSet<ObjectNode>)list).add(object);
				}
			});
		});
		return list;
	}	
	private boolean libMethodCheck(SootMethod m) {
		String methodName = m.getName();
		String className = m.getDeclaringClass().getName();
		if(className.equals("java.lang.Object")) {
			if(methodName.equals("<init>")) return true;
		} else if(className.equals("java.lang.Integer")) {
			if(methodName.equals("<init>")) return true;
		} else if(className.equals("java.io.PrintStream")) {
			if(methodName.equals("println")) return true;
		}
		if(m.isJavaLibraryMethod()){
			System.out.println("Unrecognised Library Method");
			System.out.println(className);
			System.out.println(methodName);
		}
		return false;
	}

	private void printResults() {
		StringBuilder s = new StringBuilder();
		s.append("Resolution Status:\n");
		this.resolutionStatus.forEach((method,map) -> {
			s.append(method.getSignature().toString());
			s.append("\n{\n");
			map.forEach((obj,status) -> {
				s.append("\t"+obj.toString()+" = "+status.toString()+"\n");
			});
			s.append("}\n");
		});
		s.append("Solved Summaries:\n");
		this.solvedSummaries.forEach((method,map) -> {
			s.append(method.getSignature().toString());
			s.append("\n{\n");
			map.forEach((obj,status) -> {
				s.append("\t"+obj.toString()+" = "+status.toString()+"\n");
			});
			s.append("}\n");
		});
		System.out.println(s);
	}
}
