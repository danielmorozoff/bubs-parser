package edu.ohsu.cslu.parser;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

@RunWith(Suite.class)
@Suite.SuiteClasses( { TestECPGramLoop.class, TestECPGramLoopBerkFilter.class, TestECPCellCrossHash.class,
        TestECPCellCrossList.class, TestECPCellCrossMatrix.class })
public class AllParserTests {

}
