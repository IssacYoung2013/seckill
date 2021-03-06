package issac.seckill.service.impl;

import issac.seckill.dao.SeckillDao;
import issac.seckill.dao.SuccessKilledDao;
import issac.seckill.dao.cache.RedisDao;
import issac.seckill.dto.Exposer;
import issac.seckill.dto.SeckillExecution;
import issac.seckill.entity.Seckill;
import issac.seckill.entity.SuccessKilled;
import issac.seckill.enums.SeckillStateEnum;
import issac.seckill.exception.RepeateKillException;
import issac.seckill.exception.SeckillCloseException;
import issac.seckill.exception.SeckillException;
import issac.seckill.service.SeckillService;
import org.apache.commons.collections.MapUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;

import javax.annotation.Resource;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * author:  ywy
 * date:  2018-07-04
 * desc:
 */
//@Component
@Service
public class SeckillServiceImpl implements SeckillService {

    private Logger _logger = LoggerFactory.getLogger(this.getClass());

    // 注入Service依赖 @Resrouce @Inject
    @Autowired
    private SeckillDao seckillDao;

    @Autowired
    private SuccessKilledDao successKilledDao;

    @Autowired
    private RedisDao redisDao;

    // md5盐值
    private final String slat = "lkdfjlasjfdasjfal;sldjflajskf;ja";

    public List<Seckill> getSeckillList() {
        return seckillDao.queryAll(0, 4);
    }

    public Seckill getById(long seckillId) {
        return seckillDao.queryById(seckillId);
    }

    public Exposer exportSeckillUrl(long seckillId) {
        // 1: 访问redis 超时基础上维护一致性
        Seckill seckill = redisDao.getSeckill(seckillId);
        if (seckill == null) {
            // 2：访问数据库
            seckill = seckillDao.queryById(seckillId);
            if (seckill == null) {
                return new Exposer(false, seckillId);
            } else {
                //3: 放入redis
                redisDao.putSeckill(seckill);
            }
        }

        Date startTime = seckill.getStartTime();
        Date endTiem = seckill.getEndTime();
        // 系统当前时间
        Date nowTime = new Date();
        if (nowTime.getTime() < startTime.getTime() || nowTime.getTime() > endTiem.getTime()) {
            return new Exposer(false, seckillId, nowTime.getTime(), startTime.getTime(), endTiem.getTime());
        }
        // 转化特定字符串的过程，不可逆
        String md5 = getMD5(seckillId);//TODO
        return new Exposer(true, md5, seckillId);
    }

    private String getMD5(long seckillId) {
        String base = seckillId + "/" + slat;
        String md5 = DigestUtils.md5DigestAsHex(base.getBytes());
        return md5;
    }

    @Transactional
    /**
     * 使用注解控制事务方法的优点：
     * 1:   开发团队达成一致约定，明确标注事务方法的编程风格
     * 2:   保证事务方法的执行时间尽可能的短，不要穿插其它网络操作，RPC/HTTP请求/ 或者剥离到事务方法外部
     * 3.   不是所有的方法都需要事务，如只有一条修改操作，只读操作不需要事务控制。
     */
    public SeckillExecution executeSeckill(long seckillId, long userPhone, String md5) throws SeckillException, RepeateKillException, SeckillCloseException {
        if (md5 == null || !md5.equals(getMD5(seckillId))) {
            throw new SeckillException("seckill data rewrite");
        }
        // 执行秒杀逻辑：减库存 + 记录购买行为
        Date nowTime = new Date();
        try {
            // 记录购买行为
            int insertCount = successKilledDao.insertSuccessKilled(seckillId, userPhone);
            // 唯一：seckillId,userPhone
            if (insertCount <= 0) {
                throw new RepeateKillException("seckill repeated");
            } else {
                // 减库存，热点商品竞争
                int updateCount = seckillDao.reduceNumber(seckillId, nowTime);
                if (updateCount <= 0) {
                    // 没有更新到记录,秒杀结束 rollback
                    throw new SeckillException("seckill is closed");
                } else {
                    // 秒杀成功 commit
                    SuccessKilled successKilled = successKilledDao.queryByIdWithSeckill(seckillId, userPhone);
                    return new SeckillExecution(seckillId, SeckillStateEnum.SUCCESS, successKilled);
                }
            }
        } catch (SeckillCloseException e1) {
            throw e1;
        } catch (RepeateKillException e2) {
            throw e2;
        } catch (Exception e) {
            _logger.error(e.getMessage(), e);
            // 所有编译器异常 转化为运行期异常 spring 事务rollback
            throw new SeckillException("seckill inner error: " + e.getMessage());
        }

    }

    /**
     * 执行秒杀操作 通过存储过程
     * @param seckillId
     * @param userPhone
     * @param md5
     * @return
     */
    public SeckillExecution executeSeckillProcedure(long seckillId, long userPhone, String md5) throws SeckillException, RepeateKillException, SeckillCloseException {
        if (md5 == null || !md5.equals(getMD5(seckillId))) {
            return new SeckillExecution(seckillId,SeckillStateEnum.DATA_REWRITE);
        }
        Date killTime = new Date();
        Map<String,Object> map = new HashMap<String, Object>();
        map.put("seckillId",seckillId);
        map.put("phone",userPhone);
        map.put("killTime",killTime);
        map.put("result",null);
        try {
            seckillDao.killByProcedure(map);

            // 获取result
            int result = MapUtils.getInteger(map,"result",-2);
            if(result == 1) {
                SuccessKilled sk = successKilledDao.queryByIdWithSeckill(seckillId,userPhone);
                return new SeckillExecution(seckillId,SeckillStateEnum.SUCCESS);
            }
            else {
                return new SeckillExecution(seckillId,SeckillStateEnum.stateOf(result));
            }
        }
        catch (Exception e) {
            _logger.error(e.getMessage(),e);
            return new SeckillExecution(seckillId,SeckillStateEnum.INNER_ERROR);
        }
    }
}