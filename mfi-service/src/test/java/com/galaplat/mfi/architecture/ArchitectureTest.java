package com.galaplat.mfi.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * 限界上下文的依赖方向守门。
 *
 * <p>单模块之后，上下文之间只剩包名这一层约束，而包名挡不住一次"顺手 import 了别人家的 Mapper"：
 * 那种引用看着能跑，实际会把两个上下文焊死——改 PPID 表结构要连带回归申请链路，
 * 上一轮就吃过这个亏（39 处跨上下文的 infrastructure/interfaces 引用）。
 * 所以把方向写成测试，破坏它的代码进不了主干。
 */
class ArchitectureTest {

    private static final String ROOT = "com.galaplat.mfi";

    /** 除 shared 与 apple 之外的业务上下文；apple 是防腐层，shared 是公共内核 */
    private static final List<String> CONTEXTS = List.of("credential", "identity", "ppid", "issuance");

    private static JavaClasses production;

    @BeforeAll
    static void importProductionClasses() {
        // 只导生产代码：测试里跨上下文的夹具（比如借别人的 Controller 验证响应包装）不构成运行时依赖
        production = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages(ROOT);
        // 守门测试最怕空跑：一个类都没导进来时，下面每条规则都会"通过"
        assertTrue(production.size() > 150, "生产代码导入数量异常，架构规则成了空跑: " + production.size());
    }

    /** 上下文之间不许出现环：A 依赖 B 之后 B 再依赖 A，两个上下文就得一起改 */
    @Test
    void contextsAreFreeOfCycles() {
        slices().matching(ROOT + ".(*)..").should().beFreeOfCycles().check(production);
    }

    /**
     * 跨上下文只能走端口（domain 与 application 上的接口、领域记录），
     * 不能碰别人的 infrastructure（表、Mapper、MQ、Excel）与 interfaces（Controller 与 VO）。
     */
    @Test
    void contextsTalkOnlyThroughPorts() {
        for (String provider : CONTEXTS) {
            noClasses().that().resideOutsideOfPackage(ROOT + "." + provider + "..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(ROOT + "." + provider + ".infrastructure..",
                            ROOT + "." + provider + ".interfaces..")
                    .because("上下文内部结构属于该上下文，外部只认端口")
                    .check(production);
        }
    }

    /** 苹果是防腐层：回头依赖任何业务上下文，就等于把外部世界漏进了内核 */
    @Test
    void appleStaysALeaf() {
        noClasses().that().resideInAPackage(ROOT + ".apple..")
                .should().dependOnClassesThat().resideInAnyPackage(packagesOf(otherThan("apple")))
                .because("苹果客户端不认业务，装配证书与建客户端的活由凭证上下文向上负责")
                .check(production);
    }

    /** 公共内核不得认识任何业务上下文，否则每个上下文都被内核反向绑住 */
    @Test
    void sharedKnowsNoBusinessContext() {
        noClasses().that().resideInAPackage(ROOT + ".shared..")
                .should().dependOnClassesThat().resideInAnyPackage(packagesOf(otherThan("shared")))
                .because("shared 是所有上下文的公共底座")
                .check(production);
    }

    /** 领域层不得向上依赖入站适配：VO 与 Controller 换了字段不该惊动不变式 */
    @Test
    void domainStaysOutOfInboundAdapters() {
        noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage("..interfaces..")
                .because("领域层不认 HTTP 契约")
                .check(production);
    }

    /** 除 name 之外的全部上下文（含 shared 与 apple），展开成包匹配串 */
    private static String[] packagesOf(List<String> names) {
        return names.stream().map(name -> ROOT + "." + name + "..").toArray(String[]::new);
    }

    private static List<String> otherThan(String name) {
        List<String> rest = new ArrayList<>(List.of("apple", "shared"));
        rest.addAll(CONTEXTS);
        rest.remove(name);
        return rest;
    }

}
