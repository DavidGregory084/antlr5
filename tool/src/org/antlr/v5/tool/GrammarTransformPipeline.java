/*
 * Copyright (c) 2012-present The ANTLR Project. All rights reserved.
 * Use of this file is governed by the BSD 3-clause license that
 * can be found in the LICENSE.txt file in the project root.
 */

package org.antlr.v5.tool;

import org.antlr.runtime.CommonToken;
import org.antlr.runtime.RecognitionException;
import org.antlr.runtime.tree.CommonTree;
import org.antlr.runtime.tree.CommonTreeNodeStream;
import org.antlr.runtime.tree.Tree;
import org.antlr.runtime.tree.TreeVisitor;
import org.antlr.runtime.tree.TreeVisitorAction;
import org.antlr.v5.Tool;
import org.antlr.v5.analysis.LeftRecursiveRuleTransformer;
import org.antlr.v5.parse.ANTLRParser;
import org.antlr.v5.parse.BlockSetTransformer;
import org.antlr.v5.parse.GrammarASTAdaptor;
import org.antlr.v5.parse.GrammarToken;
import org.antlr.v5.runtime.core.misc.DoubleKeyMap;
import kotlin.Pair;
import org.antlr.v5.tool.ast.AltAST;
import org.antlr.v5.tool.ast.BlockAST;
import org.antlr.v5.tool.ast.GrammarAST;
import org.antlr.v5.tool.ast.GrammarASTWithOptions;
import org.antlr.v5.tool.ast.GrammarRootAST;
import org.antlr.v5.tool.ast.RuleAST;
import org.antlr.v5.tool.ast.TerminalAST;
import org.stringtemplate.v4.ST;
import org.stringtemplate.v4.STErrorListener;
import org.stringtemplate.v4.STGroupFile;
import org.stringtemplate.v4.STGroupString;
import org.stringtemplate.v4.compiler.STException;
import org.stringtemplate.v4.misc.*;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Handle left-recursion and block-set transforms */
public class GrammarTransformPipeline {
	public Grammar g;
	public Tool tool;

	public GrammarTransformPipeline(Grammar g, Tool tool) {
		this.g = g;
		this.tool = tool;
	}

	public void process() {
		GrammarRootAST root = g.ast;
		if ( root==null ) return;
        tool.log("grammar", "before: "+root.toStringTree());

        integrateImportedGrammars(g);
		reduceBlocksToSets(root);
        expandParameterizedLoops(root);

		if (g.getActionTemplates() != null)	 {
			expandActionTemplates(root);
		}

        tool.log("grammar", "after: "+root.toStringTree());
	}

	public void reduceBlocksToSets(GrammarAST root) {
		CommonTreeNodeStream nodes = new CommonTreeNodeStream(new GrammarASTAdaptor(), root);
		GrammarASTAdaptor adaptor = new GrammarASTAdaptor();
		BlockSetTransformer transformer = new BlockSetTransformer(nodes, g);
		transformer.setTreeAdaptor(adaptor);
		transformer.downup(root);
	}

    /** Find and replace
     *      ID*[','] with ID (',' ID)*
     *      ID+[','] with ID (',' ID)+
     *      (x {action} y)+[','] with x {action} y (',' x {action} y)+
     *
     *  Parameter must be a token.
     *  todo: do we want?
     */
    public void expandParameterizedLoops(GrammarAST root) {
        TreeVisitor v = new TreeVisitor(new GrammarASTAdaptor());
        v.visit(root, new TreeVisitorAction() {
            @Override
            public Object pre(Object t) {
                if ( ((GrammarAST)t).getType() == 3 ) {
                    return expandParameterizedLoop((GrammarAST)t);
                }
                return t;
            }
            @Override
            public Object post(Object t) { return t; }
        });
    }

    public GrammarAST expandParameterizedLoop(GrammarAST t) {
        // todo: update grammar, alter AST
        return t;
    }

    /** Utility visitor that sets grammar ptr in each node */
	public static void setGrammarPtr(final Grammar g, GrammarAST tree) {
		if ( tree==null ) return;
		// ensure each node has pointer to surrounding grammar
		TreeVisitor v = new TreeVisitor(new GrammarASTAdaptor());
		v.visit(tree, new TreeVisitorAction() {
			@Override
			public Object pre(Object t) { ((GrammarAST)t).g = g; return t; }
			@Override
			public Object post(Object t) { return t; }
		});
	}

	public static void augmentTokensWithOriginalPosition(final Grammar g, GrammarAST tree) {
		if ( tree==null ) return;

		List<GrammarAST> optionsSubTrees = tree.getNodesWithType(ANTLRParser.ELEMENT_OPTIONS);
		for (int i = 0; i < optionsSubTrees.size(); i++) {
			GrammarAST t = optionsSubTrees.get(i);
			CommonTree elWithOpt = t.parent;
			if ( elWithOpt instanceof GrammarASTWithOptions ) {
				Map<String, GrammarAST> options = ((GrammarASTWithOptions) elWithOpt).getOptions();
				if ( options.containsKey(LeftRecursiveRuleTransformer.TOKENINDEX_OPTION_NAME) ) {
					GrammarToken newTok = new GrammarToken(g, elWithOpt.getToken());
					newTok.originalTokenIndex = Integer.valueOf(options.get(LeftRecursiveRuleTransformer.TOKENINDEX_OPTION_NAME).getText());
					elWithOpt.token = newTok;

					GrammarAST originalNode = g.ast.getNodeWithTokenIndex(newTok.getTokenIndex());
					if (originalNode != null) {
						// update the AST node start/stop index to match the values
						// of the corresponding node in the original parse tree.
						elWithOpt.setTokenStartIndex(originalNode.getTokenStartIndex());
						elWithOpt.setTokenStopIndex(originalNode.getTokenStopIndex());
					}
					else {
						// the original AST node could not be located by index;
						// make sure to assign valid values for the start/stop
						// index so toTokenString will not throw exceptions.
						elWithOpt.setTokenStartIndex(newTok.getTokenIndex());
						elWithOpt.setTokenStopIndex(newTok.getTokenIndex());
					}
				}
			}
		}
	}

	/** Merge all the rules, token definitions, and named actions from
		imported grammars into the root grammar tree.  Perform:

	 	(tokens { X (= Y 'y')) + (tokens { Z )	-&gt;	(tokens { X (= Y 'y') Z)

	 	(@ members {foo}) + (@ members {bar})	-&gt;	(@ members {foobar})

	 	(RULES (RULE x y)) + (RULES (RULE z))	-&gt;	(RULES (RULE x y z))

	 	Rules in root prevent same rule from being appended to RULES node.

	 	The goal is a complete combined grammar so we can ignore subordinate
	 	grammars.
	 */
	public void integrateImportedGrammars(Grammar rootGrammar) {
		List<Grammar> imports = rootGrammar.getAllImportedGrammars();
		if ( imports==null ) return;

		GrammarAST root = rootGrammar.ast;
		GrammarAST id = (GrammarAST) root.getChild(0);
		GrammarASTAdaptor adaptor = new GrammarASTAdaptor(id.token.getInputStream());

		GrammarAST channelsRoot = (GrammarAST)root.getFirstChildWithType(ANTLRParser.CHANNELS);
	 	GrammarAST tokensRoot = (GrammarAST)root.getFirstChildWithType(ANTLRParser.TOKENS_SPEC);

		List<GrammarAST> actionRoots = root.getNodesWithType(ANTLRParser.AT);

		// Compute list of rules in root grammar and ensure we have a RULES node
		GrammarAST RULES = (GrammarAST)root.getFirstChildWithType(ANTLRParser.RULES);
		Set<String> rootRuleNames = new HashSet<String>();
		// make list of rules we have in root grammar
		List<GrammarAST> rootRules = RULES.getNodesWithType(ANTLRParser.RULE);
		for (GrammarAST r : rootRules) rootRuleNames.add(r.getChild(0).getText());

		// make list of modes we have in root grammar
		List<GrammarAST> rootModes = root.getNodesWithType(ANTLRParser.MODE);
		Set<String> rootModeNames = new HashSet<String>();
		for (GrammarAST m : rootModes) rootModeNames.add(m.getChild(0).getText());
		List<GrammarAST> addedModes = new ArrayList<GrammarAST>();

		for (Grammar imp : imports) {
			// COPY CHANNELS
			GrammarAST imp_channelRoot = (GrammarAST)imp.ast.getFirstChildWithType(ANTLRParser.CHANNELS);
			if ( imp_channelRoot != null) {
				rootGrammar.tool.log("grammar", "imported channels: "+imp_channelRoot.getChildren());
				if (channelsRoot==null) {
					channelsRoot = imp_channelRoot.dupTree();
					channelsRoot.g = rootGrammar;
					root.insertChild(1, channelsRoot); // ^(GRAMMAR ID TOKENS...)
				} else {
					for (int c = 0; c < imp_channelRoot.getChildCount(); ++c) {
						String channel = imp_channelRoot.getChild(c).getText();
						boolean channelIsInRootGrammar = false;
						for (int rc = 0; rc < channelsRoot.getChildCount(); ++rc) {
							String rootChannel = channelsRoot.getChild(rc).getText();
							if (rootChannel.equals(channel)) {
								channelIsInRootGrammar = true;
								break;
							}
						}
						if (!channelIsInRootGrammar) {
                            channelsRoot.addChild(imp_channelRoot.getChild(c).dupNode());
						}
					}
				}
			}

			// COPY TOKENS
			GrammarAST imp_tokensRoot = (GrammarAST)imp.ast.getFirstChildWithType(ANTLRParser.TOKENS_SPEC);
			if ( imp_tokensRoot!=null ) {
				rootGrammar.tool.log("grammar", "imported tokens: "+imp_tokensRoot.getChildren());
				if ( tokensRoot==null ) {
					tokensRoot = (GrammarAST)adaptor.create(ANTLRParser.TOKENS_SPEC, "TOKENS");
					tokensRoot.g = rootGrammar;
					root.insertChild(1, tokensRoot); // ^(GRAMMAR ID TOKENS...)
				}
				tokensRoot.addChildren(Arrays.asList(imp_tokensRoot.getChildren().toArray(new Tree[0])));
			}

			List<GrammarAST> all_actionRoots = new ArrayList<GrammarAST>();
			List<GrammarAST> imp_actionRoots = imp.ast.getAllChildrenWithType(ANTLRParser.AT);
			if ( actionRoots!=null ) all_actionRoots.addAll(actionRoots);
			all_actionRoots.addAll(imp_actionRoots);

			// COPY ACTIONS
			if ( imp_actionRoots!=null ) {
				DoubleKeyMap<String, String, GrammarAST> namedActions =
					new DoubleKeyMap<String, String, GrammarAST>();

				rootGrammar.tool.log("grammar", "imported actions: "+imp_actionRoots);
				for (GrammarAST at : all_actionRoots) {
					String scopeName = rootGrammar.getDefaultActionScope();
					GrammarAST scope, name, action;
					if ( at.getChildCount()>2 ) { // must have a scope
						scope = (GrammarAST)at.getChild(0);
						scopeName = scope.getText();
						name = (GrammarAST)at.getChild(1);
						action = (GrammarAST)at.getChild(2);
					}
					else {
						name = (GrammarAST)at.getChild(0);
						action = (GrammarAST)at.getChild(1);
					}
					GrammarAST prevAction = namedActions.get(scopeName, name.getText());
					if ( prevAction==null ) {
						namedActions.put(scopeName, name.getText(), action);
					}
					else {
						if ( prevAction.g == at.g ) {
							rootGrammar.tool.errMgr.grammarError(ErrorType.ACTION_REDEFINITION,
												at.g.fileName, name.token, name.getText());
						}
						else {
							String s1 = prevAction.getText();
							s1 = s1.substring(1, s1.length()-1);
							String s2 = action.getText();
							s2 = s2.substring(1, s2.length()-1);
							String combinedAction = "{"+s1 + '\n'+ s2+"}";
							prevAction.token.setText(combinedAction);
						}
					}
				}
				// at this point, we have complete list of combined actions,
				// some of which are already living in root grammar.
				// Merge in any actions not in root grammar into root's tree.
				for (String scopeName : namedActions.keySet()) {
					for (String name : namedActions.keySet(scopeName)) {
						GrammarAST action = namedActions.get(scopeName, name);
						rootGrammar.tool.log("grammar", action.g.name+" "+scopeName+":"+name+"="+action.getText());
						if ( action.g != rootGrammar ) {
							root.insertChild(1, action.getParent());
						}
					}
				}
			}

			// COPY MODES
			// The strategy is to copy all the mode sections rules across to any
			// mode section in the new grammar with the same name or a new
			// mode section if no matching mode is resolved. Rules which are
			// already in the new grammar are ignored for copy. If the mode
			// section being added ends up empty it is not added to the merged
			// grammar.
            List<GrammarAST> modes = imp.ast.getNodesWithType(ANTLRParser.MODE);
			if (modes != null) {
				for (GrammarAST m : modes) {
					rootGrammar.tool.log("grammar", "imported mode: " + m.toStringTree());
					String name = m.getChild(0).getText();
					boolean rootAlreadyHasMode = rootModeNames.contains(name);
					GrammarAST destinationAST = null;
					if (rootAlreadyHasMode) {
		                for (GrammarAST m2 : rootModes) {
							if (m2.getChild(0).getText().equals(name)) {
                                destinationAST = m2;
								break;
							}
						}
					} else {
						destinationAST = m.dupNode();
						destinationAST.addChild(m.getChild(0).dupNode());
					}

					int addedRules = 0;
					List<GrammarAST> modeRules = m.getAllChildrenWithType(ANTLRParser.RULE);
					for (GrammarAST r : modeRules) {
					    rootGrammar.tool.log("grammar", "imported rule: "+r.toStringTree());
						String ruleName = r.getChild(0).getText();
					    boolean rootAlreadyHasRule = rootRuleNames.contains(ruleName);
					    if (!rootAlreadyHasRule) {
						    destinationAST.addChild(r);
							addedRules++;
						    rootRuleNames.add(ruleName);
					    }
					}
					if (!rootAlreadyHasMode && addedRules > 0) {
						rootGrammar.ast.addChild(destinationAST);
						rootModeNames.add(name);
						rootModes.add(destinationAST);
					}
				}
			}

			// COPY RULES
			// Rules copied in the mode copy phase are not copied again.
			List<GrammarAST> rules = imp.ast.getNodesWithType(ANTLRParser.RULE);
			if ( rules!=null ) {
				for (GrammarAST r : rules) {
					rootGrammar.tool.log("grammar", "imported rule: "+r.toStringTree());
					String name = r.getChild(0).getText();
					boolean rootAlreadyHasRule = rootRuleNames.contains(name);
					if ( !rootAlreadyHasRule ) {
						RULES.addChild(r); // merge in if not overridden
						rootRuleNames.add(name);
					}
				}
			}

			GrammarAST optionsRoot = (GrammarAST)imp.ast.getFirstChildWithType(ANTLRParser.OPTIONS);
			if ( optionsRoot!=null ) {
				// suppress the warning if the options match the options specified
				// in the root grammar
				// https://github.com/antlr/antlr4/issues/707

				boolean hasNewOption = false;
				for (Map.Entry<String, GrammarAST> option : imp.ast.getOptions().entrySet()) {
					String importOption = imp.ast.getOptionString(option.getKey());
					if (importOption == null) {
						continue;
					}

					String rootOption = rootGrammar.ast.getOptionString(option.getKey());
					if (!importOption.equals(rootOption)) {
						hasNewOption = true;
						break;
					}
				}

				if (hasNewOption) {
					rootGrammar.tool.errMgr.grammarError(ErrorType.OPTIONS_IN_DELEGATE,
										optionsRoot.g.fileName, optionsRoot.token, imp.name);
				}
			}
		}
		rootGrammar.tool.log("grammar", "Grammar: "+rootGrammar.ast.toStringTree());
	}

	/** Build lexer grammar from combined grammar that looks like:
	 *
	 *  (COMBINED_GRAMMAR A
	 *      (tokens { X (= Y 'y'))
	 *      (OPTIONS (= x 'y'))
	 *      (@ members {foo})
	 *      (@ lexer header {package jj;})
	 *      (RULES (RULE .+)))
	 *
	 *  Move rules and actions to new tree, don't dup. Split AST apart.
	 *  We'll have this Grammar share token symbols later; don't generate
	 *  tokenVocab or tokens{} section.  Copy over named actions.
	 *
	 *  Side-effects: it removes children from GRAMMAR &amp; RULES nodes
	 *                in combined AST.  Anything cut out is dup'd before
	 *                adding to lexer to avoid "who's ur daddy" issues
	 */
	public GrammarRootAST extractImplicitLexer(Grammar combinedGrammar) {
		GrammarRootAST combinedAST = combinedGrammar.ast;
		//tool.log("grammar", "before="+combinedAST.toStringTree());
		GrammarASTAdaptor adaptor = new GrammarASTAdaptor(combinedAST.token.getInputStream());
		GrammarAST[] elements = combinedAST.getChildren().toArray(new GrammarAST[0]);

		// MAKE A GRAMMAR ROOT and ID
		String lexerName = combinedAST.getChild(0).getText()+"Lexer";
		GrammarRootAST lexerAST =
		    new GrammarRootAST(new CommonToken(ANTLRParser.GRAMMAR, "LEXER_GRAMMAR"), combinedGrammar.ast.tokenStream);
		lexerAST.grammarType = ANTLRParser.LEXER;
		lexerAST.token.setInputStream(combinedAST.token.getInputStream());
		lexerAST.addChild((GrammarAST)adaptor.create(ANTLRParser.ID, lexerName));

		// COPY OPTIONS
		GrammarAST optionsRoot =
			(GrammarAST)combinedAST.getFirstChildWithType(ANTLRParser.OPTIONS);
		if ( optionsRoot!=null && optionsRoot.getChildCount()!=0 ) {
			GrammarAST lexerOptionsRoot = (GrammarAST)adaptor.dupNode(optionsRoot);
			lexerAST.addChild(lexerOptionsRoot);
			GrammarAST[] options = optionsRoot.getChildren().toArray(new GrammarAST[0]);
			for (GrammarAST o : options) {
				String optionName = o.getChild(0).getText();
				if ( Grammar.lexerOptions.contains(optionName) &&
					 !Grammar.doNotCopyOptionsToLexer.contains(optionName) )
				{
					GrammarAST optionTree = (GrammarAST)adaptor.dupTree(o);
					lexerOptionsRoot.addChild(optionTree);
					lexerAST.setOption(optionName, (GrammarAST)optionTree.getChild(1));
				}
			}
		}

		// COPY all named actions, but only move those with lexer:: scope
		List<GrammarAST> actionsWeMoved = new ArrayList<GrammarAST>();
		for (GrammarAST e : elements) {
			if ( e.getType()==ANTLRParser.AT ) {
				lexerAST.addChild((Tree)adaptor.dupTree(e));
				if ( e.getChild(0).getText().equals("lexer") ) {
					actionsWeMoved.add(e);
				}
			}
		}

		for (GrammarAST r : actionsWeMoved) {
			combinedAST.deleteChild( r );
		}

		GrammarAST combinedRulesRoot =
			(GrammarAST)combinedAST.getFirstChildWithType(ANTLRParser.RULES);
		if ( combinedRulesRoot==null ) return lexerAST;

		// MOVE lexer rules

		GrammarAST lexerRulesRoot =
			(GrammarAST)adaptor.create(ANTLRParser.RULES, "RULES");
		lexerAST.addChild(lexerRulesRoot);
		List<GrammarAST> rulesWeMoved = new ArrayList<GrammarAST>();
		GrammarASTWithOptions[] rules;
		if (combinedRulesRoot.getChildCount() > 0) {
			rules = combinedRulesRoot.getChildren().toArray(new GrammarASTWithOptions[0]);
		}
		else {
			rules = new GrammarASTWithOptions[0];
		}

		for (GrammarASTWithOptions r : rules) {
			String ruleName = r.getChild(0).getText();
			if (Grammar.isTokenName(ruleName)) {
				lexerRulesRoot.addChild((Tree)adaptor.dupTree(r));
				rulesWeMoved.add(r);
			}
		}
		for (GrammarAST r : rulesWeMoved) {
			combinedRulesRoot.deleteChild( r );
		}

		// Will track 'if' from IF : 'if' ; rules to avoid defining new token for 'if'
		List<Pair<GrammarAST,GrammarAST>> litAliases =
			Grammar.getStringLiteralAliasesFromLexerRules(lexerAST);

		Set<String> stringLiterals = combinedGrammar.getStringLiterals();
		// add strings from combined grammar (and imported grammars) into lexer
		// put them first as they are keywords; must resolve ambigs to these rules
//		tool.log("grammar", "strings from parser: "+stringLiterals);
		int insertIndex = 0;
		nextLit:
		for (String lit : stringLiterals) {
			// if lexer already has a rule for literal, continue
			if ( litAliases!=null ) {
				for (Pair<GrammarAST,GrammarAST> pair : litAliases) {
					GrammarAST litAST = pair.getSecond();
					if ( lit.equals(litAST.getText()) ) continue nextLit;
				}
			}
			// create for each literal: (RULE <uniquename> (BLOCK (ALT <lit>))
			String rname = combinedGrammar.getStringLiteralLexerRuleName(lit);
			// can't use wizard; need special node types
			GrammarAST litRule = new RuleAST(ANTLRParser.RULE);
			BlockAST blk = new BlockAST(ANTLRParser.BLOCK);
			AltAST alt = new AltAST(ANTLRParser.ALT);
			TerminalAST slit = new TerminalAST(new CommonToken(ANTLRParser.STRING_LITERAL, lit));
			alt.addChild(slit);
			blk.addChild(alt);
			CommonToken idToken = new CommonToken(ANTLRParser.TOKEN_REF, rname);
			litRule.addChild(new TerminalAST(idToken));
			litRule.addChild(blk);
			lexerRulesRoot.insertChild(insertIndex, litRule);
//			lexerRulesRoot.getChildren().add(0, litRule);
			lexerRulesRoot.freshenParentAndChildIndexes(); // reset indexes and set litRule parent

			// next literal will be added after the one just added
			insertIndex++;
		}

		// TODO: take out after stable if slow
		lexerAST.sanityCheckParentAndChildIndexes();
		combinedAST.sanityCheckParentAndChildIndexes();
//		tool.log("grammar", combinedAST.toTokenString());

        combinedGrammar.tool.log("grammar", "after extract implicit lexer ="+combinedAST.toStringTree());
        combinedGrammar.tool.log("grammar", "lexer ="+lexerAST.toStringTree());

		if ( lexerRulesRoot.getChildCount()==0 )	return null;
		return lexerAST;
	}

	/**
	 * Create an error listener for .stg action template group files provided via grammar options or via the command line.
	 */
	private STErrorListener createActionTemplateErrorListener(GrammarAST ast, STGroupFile actionTemplateGroupFile) {
		return new STErrorListener() {
			private final ErrorManager errorManager = ast.g.tool.errMgr;

			@Override
			public void compileTimeError(STMessage stMessage) {
				errorManager.toolError(
					ErrorType.ERROR_COMPILING_ACTION_TEMPLATES_FILE,
					actionTemplateGroupFile.fileName,
					stMessage.toString());
			}

			@Override
			public void runTimeError(STMessage stMessage) {
				errorManager.toolError(
					ErrorType.ERROR_RENDERING_ACTION_TEMPLATES_FILE,
					actionTemplateGroupFile.fileName,
					stMessage.toString());
			}

			@Override
			public void IOError(STMessage stMessage) {
				reportInternalError(stMessage);
			}

			@Override
			public void internalError(STMessage stMessage) {
				reportInternalError(stMessage);
			}

			private void reportInternalError(STMessage stMessage) {
				errorManager.toolError(ErrorType.INTERNAL_ERROR, stMessage.cause, stMessage.toString());
			}
		};
	}

	/**
	 * Create an error listener for action templates embedded inside a grammar's actions and semantic predicates.
	 */
	private STErrorListener createGrammarErrorListener(GrammarAST ast) {
		return new STErrorListener() {
			private final ErrorManager errorManager = ast.g.tool.errMgr;

			/**
			 * Get the STCompiletimeMesage error message content, translating the source location
			 * according to the action token's position in the grammar.
			 */
			private String getSTCompiletimeErrorMessage(STCompiletimeMessage stMsg) {
				StringWriter sw = new StringWriter();
				PrintWriter pw = new PrintWriter(sw);

				if (stMsg.token != null) {
					int linePos = ast.getLine() + stMsg.token.getLine() - 1;
					int charPos = stMsg.token.getCharPositionInLine();
					if (stMsg.token.getLine() == 1) {
						charPos += ast.getCharPositionInLine();
					}
					pw.print(linePos + ":" + charPos + ": ");
				}

				String msg = String.format(stMsg.error.message, stMsg.arg, stMsg.arg2);

				pw.print(msg);

				return sw.toString();
			}

			/**
			 * Get the STLexerMessage error message content, translating the source location
			 * according to the action token's position in the grammar.
			 */
			private String getSTLexerErrorMessage(STLexerMessage stMsg) {
				StringWriter sw = new StringWriter();
				PrintWriter pw = new PrintWriter(sw);

				// From STLexerMessage.toString
				RecognitionException re = (RecognitionException)stMsg.cause;
				int linePos = ast.getLine() + re.line - 1;
				int charPos = re.charPositionInLine;
				if (re.line == 1) {
					charPos += ast.getCharPositionInLine();
				}
				pw.print(linePos + ":" + charPos + ": ");

				String msg = String.format(stMsg.error.message, stMsg.msg);

				pw.print(msg);

				return sw.toString();
			}

			@Override
			public void compileTimeError(STMessage stMessage) {
				if (stMessage instanceof STCompiletimeMessage) {
					STCompiletimeMessage compileMessage = (STCompiletimeMessage) stMessage;
					errorManager.grammarError(ErrorType.ERROR_COMPILING_ACTION_TEMPLATE, ast.g.fileName, ast.getToken(), getSTCompiletimeErrorMessage(compileMessage));
				}
				else if (stMessage instanceof STLexerMessage) {
					STLexerMessage lexerMessage = (STLexerMessage) stMessage;
					errorManager.grammarError(ErrorType.ERROR_COMPILING_ACTION_TEMPLATE, ast.g.fileName, ast.getToken(), getSTLexerErrorMessage(lexerMessage));
				}
				else {
					errorManager.grammarError(ErrorType.ERROR_COMPILING_ACTION_TEMPLATE, ast.g.fileName, ast.getToken(), stMessage.toString());
				}
			}

			/**
			 * Get the STRuntimeMessage error location Coordinate.
			 */
			private Coordinate getSTRuntimeMessageSourceLocation(STRuntimeMessage msg) {
				// From STRuntimeMessage.getSourceLocation
				if (msg.ip >= 0 && msg.self != null && msg.self.impl != null) {
					Interval I = msg.self.impl.sourceMap[msg.ip];
					if (I == null) {
						return null;
					} else {
						int i = I.a;
						return Misc.getLineCharPosition(msg.self.impl.template, i);
					}
				} else {
					return null;
				}
			}

			/**
			 * Get the STRuntimeMessage error message content, translating the source location
			 * according to the action token's position in the grammar.
			 */
			private String getSTRuntimeErrorMessage(STRuntimeMessage stMsg) {
				StringWriter sw = new StringWriter();
				PrintWriter pw = new PrintWriter(sw);
				Coordinate coord = getSTRuntimeMessageSourceLocation(stMsg);

				if (coord != null) {
					int linePos = ast.getLine() + coord.line - 1;
					int charPos = coord.charPosition;
					if (coord.line == 1) {
						charPos += ast.getCharPositionInLine();
					}
					pw.print(linePos + ":" + charPos + ": ");
				}

				// From STMessage.toString
				String msg = String.format(stMsg.error.message, stMsg.arg, stMsg.arg2, stMsg.arg3);

				pw.print(msg);

				if (stMsg.cause != null) {
					pw.print("\nCaused by: ");
					stMsg.cause.printStackTrace(pw);
				}

				return sw.toString();
			}

			@Override
			public void runTimeError(STMessage stMessage) {
				if (stMessage instanceof STRuntimeMessage) {
					STRuntimeMessage runtimeMessage = (STRuntimeMessage) stMessage;
					errorManager.grammarError(
						ErrorType.ERROR_RENDERING_ACTION_TEMPLATE,
						ast.g.fileName,
						ast.getToken(),
						getSTRuntimeErrorMessage(runtimeMessage));
				} else {
					errorManager.grammarError(
						ErrorType.ERROR_RENDERING_ACTION_TEMPLATE,
						ast.g.fileName,
						ast.getToken(),
						stMessage.toString());
				}
			}

			@Override
			public void IOError(STMessage stMessage) {
				reportError(stMessage);
			}

			@Override
			public void internalError(STMessage stMessage) {
				reportError(stMessage);
			}

			private void reportError(STMessage stMessage) {
				errorManager.toolError(ErrorType.INTERNAL_ERROR, stMessage.cause, stMessage.toString());
			}
		};
	}

	public File getActionTemplatesGroupFile(GrammarRootAST root, String actionTemplates) {
		// Try for an absolute path
		File importedFile = new File(actionTemplates);

		if (!importedFile.exists()) {
			// Next try the input directory
			importedFile = new File(root.g.tool.inputDirectory, actionTemplates);
			if (!importedFile.exists()) {
				// Next try the parent directory of the grammar file
				File grammarFile = new File(root.g.fileName);
				String parentDir = grammarFile.getParent();
				importedFile = new File(parentDir, actionTemplates);
				if (!importedFile.exists()) {
					// Next try the lib directory
					importedFile = new File(root.g.tool.libDirectory, actionTemplates);
					if (!importedFile.exists()) {
						return null;
					}
				}
			}
		}

		return importedFile;
	}

	public void cannotFindActionTemplatesFileError(GrammarRootAST root) {
		Grammar grammar = root.g;
		ErrorManager errorManager = grammar.tool.errMgr;
		String actionTemplatesFile = grammar.getActionTemplates();

		// Check whether this action template file came from options in the grammar file
		GrammarAST optionAST = root.getOptionAST("actionTemplates");

		if (optionAST != null && actionTemplatesFile.equals(optionAST.getToken().getText())) {
			errorManager.grammarError(
				ErrorType.CANNOT_FIND_ACTION_TEMPLATES_FILE_REFD_IN_GRAMMAR,
				grammar.fileName,
				optionAST.getToken(), actionTemplatesFile);
		} else {
			errorManager.toolError(
				ErrorType.CANNOT_FIND_ACTION_TEMPLATES_FILE_GIVEN_ON_CMDLINE,
				actionTemplatesFile,
				grammar.name);
		}
	}

	public STGroupFile loadActionTemplatesGroupFile(GrammarRootAST root) {
		Grammar grammar = root.g;
		ErrorManager errorManager = grammar.tool.errMgr;
		String actionTemplatesFile = grammar.getActionTemplates();
		File actionTemplatesGroupFile = getActionTemplatesGroupFile(root, actionTemplatesFile);

		if (actionTemplatesGroupFile == null) {
			cannotFindActionTemplatesFileError(root);
			return null;
		}

		try {
			STGroupFile actionTemplates = new STGroupFile(actionTemplatesGroupFile.getAbsolutePath());
			STErrorListener errorListener = createActionTemplateErrorListener(root, actionTemplates);

			// Force load the action templates group file
			actionTemplates.setListener(errorListener);
			actionTemplates.load();

			return actionTemplates;

		} catch (IllegalArgumentException e) {
			if (e.getMessage() != null && e.getMessage().startsWith("No such group file")) {
				cannotFindActionTemplatesFileError(root);
			} else {
				errorManager.toolError(
					ErrorType.ERROR_READING_ACTION_TEMPLATES_FILE, e,
					actionTemplatesFile,
					e.getMessage());
			}
		} catch (STException e) {
			errorManager.toolError(
				ErrorType.ERROR_READING_ACTION_TEMPLATES_FILE, e,
				actionTemplatesFile,
				e.getMessage());
		}

		return null;
	}

	public void expandActionTemplates(GrammarRootAST root) {
		Grammar grammar = root.g;
		ErrorManager errorManager = grammar.tool.errMgr;

		STGroupFile actionTemplates = loadActionTemplatesGroupFile(root);

		if (actionTemplates != null) {
			TreeVisitor visitor = new TreeVisitor(new GrammarASTAdaptor());
			visitor.visit(root, new TreeVisitorAction() {
				@Override
				public Object pre(Object t) {
					GrammarAST grammarAST = (GrammarAST) t;
					int tokenType = grammarAST.getType();
					if (tokenType == ANTLRParser.ACTION || tokenType == ANTLRParser.SEMPRED) {
						return expandActionTemplate((GrammarAST) t, errorManager, actionTemplates);
					}
					return t;
				}
				@Override
				public Object post(Object t) {
					return t;
				}
			});
		}
	}

	public GrammarAST expandActionTemplate(GrammarAST ast, ErrorManager errMgr, STGroupFile actionTemplateGroupFile) {
		// Trim the curly braces and trailing question mark
		// from an action or semantic predicate
		String actionText = ast.getText()
			.substring(1,
				ast.getType() == ANTLRParser.SEMPRED
					? ast.getText().length() - 2
					: ast.getText().length() - 1);

		STGroupString actionTemplateGroup = new STGroupString(
			ast.g.fileName, "action() ::= << " + actionText + " >>");

		actionTemplateGroup.importTemplates(actionTemplateGroupFile);

		actionTemplateGroup.setListener(createGrammarErrorListener(ast));

		ST actionTemplate = actionTemplateGroup.getInstanceOf("action");

		if (actionTemplate != null) {
			ast.setText(actionTemplate.render());
		}

		return ast;
	}
}
